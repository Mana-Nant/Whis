package com.example.whisperandroid.data.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * モデルのダウンロード・インポート・削除を担当。
 *
 * - DL先: 内部ストレージ files/models/ (ユーザー可視性不要、アプリ削除で消える)
 * - OkHttpで直接ダウンロード(DownloadManagerは内部ストレージへ書けないため不採用)
 * - SAFインポート: ユーザー選択の .bin を内部ディレクトリへコピー
 */
class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val MODELS_DIR = "models"
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun listInstalled(): List<File> =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun isInstalled(model: WhisperModel): Boolean =
        File(modelsDir, model.fileName).exists()

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    /**
     * モデルをHugging Faceから直接ダウンロードする。
     * 進捗 (0..100) を流し、最後に100で終了。失敗時は例外で完了。
     *
     * 既存ファイルがあれば上書き。途中失敗時は .part が残るので次回も再取得。
     */
    fun downloadModel(model: WhisperModel): Flow<Int> = flow {
        val dest = File(modelsDir, model.fileName)
        val tmp = File(modelsDir, "${model.fileName}.part")
        if (tmp.exists()) tmp.delete()

        val req = Request.Builder().url(model.downloadUrl).build()
        Log.i(TAG, "downloading ${model.downloadUrl}")

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code} for ${model.fileName}")
            }
            val body = resp.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L

            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var soFar = 0L
                    var lastEmit = -1
                    emit(0)
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        soFar += read
                        if (total > 0) {
                            val p = ((soFar * 100) / total).toInt().coerceIn(0, 99)
                            if (p != lastEmit) {
                                emit(p)
                                lastEmit = p
                            }
                        }
                    }
                    out.flush()
                }
            }
        }
        // .part → 正規ファイルへリネーム
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        emit(100)
        Log.i(TAG, "saved: ${dest.absolutePath} (${dest.length()} bytes)")
    }.flowOn(Dispatchers.IO)

    /** SAFで選択された .bin を内部ディレクトリへコピー */
    suspend fun importFromUri(uri: Uri, displayName: String): File? =
        withContext(Dispatchers.IO) {
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
