package com.example.whisperandroid.whisper

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * whisper.cpp ネイティブライブラリへのKotlinラッパ。
 *
 * ライフサイクル:
 *   loadModel(path) → transcribe(wav) → release()
 *
 * 設計書どおり language="ja" 固定、タイムスタンプなし、use_mmap=true。
 * 推論が終わったら必ず release() を呼び、ネイティブメモリを解放する。
 */
class WhisperBridge {

    companion object {
        private const val TAG = "WhisperBridge"

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    interface BridgeListener {
        /** 0..100 */
        fun onProgress(progress: Int)
    }

    private val ctxPtr = AtomicLong(0L)

    /**
     * モデルをロード。既にロード済みなら一度解放してから再ロード。
     * @param modelPath GGUFモデルファイルの絶対パス
     */
    @Synchronized
    fun loadModel(modelPath: String, useMmap: Boolean = true) {
        require(File(modelPath).exists()) { "Model file not found: $modelPath" }
        release()
        val p = nativeInitContext(modelPath, useMmap)
        if (p == 0L) error("Failed to load whisper model: $modelPath")
        ctxPtr.set(p)
        Log.i(TAG, "Model loaded: $modelPath (ptr=$p)")
    }

    /**
     * 事前に16kHz/mono/pcm_s16leに変換済みのWAVを推論。
     * @param wavPath 16kHzモノラルPCMのWAV
     * @param threads スレッド数（既定4）
     * @param listener 進捗コールバック (0..100)
     * @return 文字起こし結果
     */
    fun transcribe(
        wavPath: String,
        threads: Int = 4,
        listener: BridgeListener? = null
    ): String {
        val p = ctxPtr.get()
        check(p != 0L) { "Model not loaded" }
        return nativeTranscribe(p, wavPath, threads, listener) ?: ""
    }

    @Synchronized
    fun release() {
        val p = ctxPtr.getAndSet(0L)
        if (p != 0L) {
            nativeFreeContext(p)
            Log.i(TAG, "Model released")
        }
    }

    fun systemInfo(): String = nativeSystemInfo()

    // ---- Native ----
    private external fun nativeInitContext(modelPath: String, useMmap: Boolean): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long, wavPath: String, nThreads: Int, listener: BridgeListener?
    ): String?
    private external fun nativeSystemInfo(): String
}
