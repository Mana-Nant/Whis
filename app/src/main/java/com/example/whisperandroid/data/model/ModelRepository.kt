package com.example.whisperandroid.data.model

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * モデルのダウンロード・インポート・削除を担当。
 *
 * - DL先: 内部ストレージの `files/models/` (ユーザー可視性不要、アプリ削除で一緒に消える)
 * - DownloadManager使用: 数百MB〜数GBのファイルでも安定取得
 * - SAFインポート: ユーザー選択の `.bin` を内部ストレージへコピー
 */
class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val MODELS_DIR = "models"
    }

    private val dm: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /** ローカルに存在するモデルファイルの一覧を返す。 */
    fun listInstalled(): List<File> =
        modelsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }?.sortedBy { it.name }
            ?: emptyList()

    fun isInstalled(model: WhisperModel): Boolean =
        File(modelsDir, model.fileName).exists()

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    /**
     * DownloadManager経由でモデルDLを開始する。
     * 進捗は [observeDownload] から流す。
     * @return enqueue済みのdownload ID
     */
    fun enqueueDownload(model: WhisperModel): Long {
        val dest = File(modelsDir, model.fileName)
        if (dest.exists()) dest.delete()

        val req = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle(model.displayName)
            .setDescription("Whisperモデルをダウンロード中")
            .setDestinationUri(Uri.fromFile(dest))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val id = dm.enqueue(req)
        Log.i(TAG, "enqueued ${model.fileName} id=$id")
        return id
    }

    /** DL完了通知を購読（true=成功 / false=失敗）。 */
    fun observeDownload(downloadId: Long): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                val q = DownloadManager.Query().setFilterById(id)
                dm.query(q)?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        trySend(status == DownloadManager.STATUS_SUCCESSFUL)
                    }
                }
                close()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * DL中の進捗 (0..100) をポーリングで流す。軽量な500ms刻み。
     */
    fun pollProgress(downloadId: Long): Flow<Int> = flow {
        while (true) {
            val q = DownloadManager.Query().setFilterById(downloadId)
            var done = false
            dm.query(q)?.use { c ->
                if (c.moveToFirst()) {
                    val so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (total > 0) emit(((so * 100) / total).toInt())
                    if (status == DownloadManager.STATUS_SUCCESSFUL ||
                        status == DownloadManager.STATUS_FAILED) done = true
                } else done = true
            } ?: run { done = true }
            if (done) break
            kotlinx.coroutines.delay(500)
        }
    }

    /** SAFで選択された `.bin` を内部ディレクトリへコピーする。 */
    suspend fun importFromUri(uri: Uri, displayName: String): File? = withContext(Dispatchers.IO) {
        val name = if (displayName.endsWith(".bin")) displayName else "$displayName.bin"
        val dest = File(modelsDir, name)
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open $uri" }
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest
        }.onFailure { Log.e(TAG, "importFromUri failed", it) }.getOrNull()
    }

    suspend fun delete(model: WhisperModel): Boolean = withContext(Dispatchers.IO) {
        File(modelsDir, model.fileName).takeIf { it.exists() }?.delete() ?: false
    }

    suspend fun deleteFile(file: File): Boolean = withContext(Dispatchers.IO) {
        file.takeIf { it.exists() }?.delete() ?: false
    }
}
