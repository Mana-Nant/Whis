package com.example.whisperandroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.example.whisperandroid.WhisperApplication
import com.example.whisperandroid.audio.AudioConverter
import com.example.whisperandroid.data.db.TranscriptionEntity
import com.example.whisperandroid.util.FileUtils
import com.example.whisperandroid.whisper.WhisperBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 音声変換 → whisper 推論 → DB保存 → (任意で)自動エクスポート
 * のパイプライン一式を Foreground Service で実行する。
 *
 * UIからは [TranscriptionService.progress] / [state] を観測して進捗を表示する。
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "TranscriptionService"

        const val ACTION_START = "com.example.whisperandroid.action.START"
        const val ACTION_CANCEL = "com.example.whisperandroid.action.CANCEL"

        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_INPUT_NAME = "input_name"
        const val EXTRA_MODEL_PATH = "model_path"

        // UI ⇔ Service のライトな連携用に StateFlow で公開（Bindなし）
        private val _progress = MutableStateFlow(0)
        val progress: StateFlow<Int> = _progress.asStateFlow()

        private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val state: StateFlow<ServiceState> = _state.asStateFlow()
    }

    sealed class ServiceState {
        data object Idle : ServiceState()
        data class Converting(val fileName: String) : ServiceState()
        data class Transcribing(val fileName: String) : ServiceState()
        data class Completed(val transcriptionId: Long, val text: String) : ServiceState()
        data class Failed(val message: String) : ServiceState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val whisper = WhisperBridge()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelJob()
                stopSelfSafely()
            }
            else -> {
                val uriStr = intent?.getStringExtra(EXTRA_INPUT_URI)
                val name = intent?.getStringExtra(EXTRA_INPUT_NAME) ?: "audio"
                val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
                if (uriStr == null || modelPath == null) {
                    Log.e(TAG, "missing params; stop")
                    stopSelfSafely()
                } else {
                    startProcessing(uriStr.toUri(), name, modelPath)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProcessing(uri: Uri, inputName: String, modelPath: String) {
        // すでに動いている場合は無視
        if (currentJob?.isActive == true) return

        val notif = NotificationHelper.build(
            this, "文字起こしを準備中", inputName, indeterminate = true
        )
        startForeground(NotificationHelper.NOTIF_ID, notif)

        acquireWakeLock()

        currentJob = scope.launch {
            val app = applicationContext as WhisperApplication
            var tempWav: File? = null
            var cachedInput: File? = null
            val started = System.currentTimeMillis()
            try {
                // 1) URI→ローカルキャッシュへコピー（FFmpegKitはファイルパス入力が安全）
                _state.value = ServiceState.Converting(inputName)
                updateNotif("音声を変換中 (前処理)", inputName, indeterminate = true)
                cachedInput = FileUtils.copyUriToCache(this@TranscriptionService, uri, inputName)
                    ?: error("入力音声のコピーに失敗")

                // 2) 16kHz/mono WAVへ変換
                tempWav = File(cacheDir, "in_${System.currentTimeMillis()}.wav")
                val wav = AudioConverter.convertTo16kMono(cachedInput.absolutePath, tempWav)
                    ?: error("FFmpegによる音声変換に失敗")

                // 3) モデルロード + 推論
                _state.value = ServiceState.Transcribing(inputName)
                updateNotif("文字起こし中", "$inputName  0%", progress = 0)

                whisper.loadModel(modelPath, useMmap = true)

                val threads = preferredThreadCount(app)
                val text = whisper.transcribe(
                    wavPath = wav.absolutePath,
                    threads = threads,
                    listener = object : WhisperBridge.BridgeListener {
                        override fun onProgress(progress: Int) {
                            _progress.value = progress
                            updateNotif("文字起こし中", "$inputName  $progress%", progress = progress)
                        }
                    }
                ).trim()

                val elapsed = System.currentTimeMillis() - started
                if (text.isEmpty()) error("推論結果が空でした")

                // 4) DB保存
                val modelName = File(modelPath).name
                val entity = TranscriptionEntity(
                    sourceName = inputName,
                    sourceUri = uri.toString(),
                    modelName = modelName,
                    text = text,
                    inferenceMs = elapsed
                )
                val id = app.database.transcriptionDao().insert(entity)

                // 5) 自動エクスポート
                val prefs = app.preferences
                if (prefs.autoExportEnabled) {
                    prefs.defaultExportTreeUri?.toUri()?.let { tree ->
                        val outName = inputName.substringBeforeLast('.', inputName) + ".txt"
                        FileUtils.writeTextToTree(this@TranscriptionService, tree, outName, text)
                    }
                }

                _state.value = ServiceState.Completed(id, text)
                showDoneNotification(inputName)

            } catch (t: Throwable) {
                Log.e(TAG, "pipeline failed", t)
                _state.value = ServiceState.Failed(t.message ?: "Unknown error")
                showFailNotification(inputName, t.message ?: "エラー")
            } finally {
                // ネイティブメモリ解放（推奨どおり即座に）
                whisper.release()
                // 一時WAV・一時inputを削除
                tempWav?.takeIf { it.exists() }?.delete()
                cachedInput?.takeIf { it.exists() }?.delete()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelfSafely()
            }
        }
    }

    private fun preferredThreadCount(app: WhisperApplication): Int {
        val setting = app.preferences.inferenceThreads
        if (setting > 0) return setting
        val cpu = Runtime.getRuntime().availableProcessors()
        return cpu.coerceIn(2, 6)
    }

    private fun updateNotif(
        title: String,
        content: String,
        progress: Int? = null,
        indeterminate: Boolean = false
    ) {
        val n = NotificationHelper.build(
            this, title, content,
            progress = progress, indeterminate = indeterminate, ongoing = true
        )
        NotificationManagerCompat.from(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !areNotificationsEnabled()) return
            notify(NotificationHelper.NOTIF_ID, n)
        }
    }

    private fun showDoneNotification(name: String) {
        val n = NotificationHelper.build(
            this, "文字起こしが完了", name, ongoing = false
        )
        NotificationManagerCompat.from(this).notify(NotificationHelper.NOTIF_ID + 1, n)
    }

    private fun showFailNotification(name: String, msg: String) {
        val n = NotificationHelper.build(
            this, "文字起こしに失敗", "$name: $msg", ongoing = false
        )
        NotificationManagerCompat.from(this).notify(NotificationHelper.NOTIF_ID + 2, n)
    }

    private fun cancelJob() {
        currentJob?.cancel()
        whisper.release()
    }

    private fun stopSelfSafely() {
        scope.launch { /* flush */ }
        stopSelf()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whisper:transcribe").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 最大30分
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        whisper.release()
        scope.cancel()
        releaseWakeLock()
    }
}
