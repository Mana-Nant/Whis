package com.example.whisperandroid.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioRecord でマイクから直接 16kHz/mono/16-bit PCM を録音し、
 * WAV(.wav) ファイルとして書き出す。
 *
 * Whisperの入力仕様にちょうど一致するため、録音後のリサンプリングは不要。
 * 開始 → 停止 で1ファイル生成。途中での一時停止/再開は未対応(必要なら後付け)。
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var recording = false
    private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = recording

    /**
     * 録音開始。出力先WAVファイルを返す(録音中は44バイトのヘッダだけ先に書かれる)。
     * @param outputFile 書き込み先(存在すれば上書き)
     * @param onLevel 簡易音量レベル(0..1)を約100msごとに通知。プログレスバーに使える
     */
    @SuppressLint("MissingPermission") // 呼び出し側で RECORD_AUDIO 権限を確認
    fun start(outputFile: File, onLevel: (Float) -> Unit = {}) {
        if (recording) return
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE) // 1秒分以上

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord 初期化失敗")
        }
        recorder = rec

        // WAVヘッダ(仮、長さは録音終了後に書き戻す)を先頭に書いておく
        FileOutputStream(outputFile).use { it.write(ByteArray(44)) }

        recording = true
        rec.startRecording()
        Log.i(TAG, "recording started → ${outputFile.absolutePath}")

        job = scope.launch {
            val out = FileOutputStream(outputFile, /*append*/ true)
            val buf = ShortArray(bufSize / 2)
            var totalBytes = 0L
            try {
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        // PCM16-LE で書き込み
                        val byteOut = ByteArray(n * 2)
                        val bb = ByteBuffer.wrap(byteOut).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) bb.putShort(buf[i])
                        out.write(byteOut)
                        totalBytes += byteOut.size

                        // 簡易レベル算出
                        var sumSq = 0.0
                        for (i in 0 until n) {
                            val s = buf[i] / 32768f
                            sumSq += (s * s).toDouble()
                        }
                        val rms = kotlin.math.sqrt(sumSq / n).toFloat()
                        onLevel(rms.coerceIn(0f, 1f))
                    }
                }
            } finally {
                out.close()
                runCatching { rec.stop() }
                rec.release()
                recorder = null
                writeWavHeader(outputFile, totalBytes)
                Log.i(TAG, "recording stopped, $totalBytes bytes")
            }
        }
    }

    /** 録音停止。コルーチンが完了するまで待つわけではない(呼び出し側でファイル確定を別途確認) */
    fun stop() {
        recording = false
        job?.let { /* 完了待ちはUI側 */ }
    }

    fun cancel() {
        recording = false
        job?.cancel()
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
    }

    /** 録音終了後にWAVヘッダの長さフィールドを書き戻す */
    private fun writeWavHeader(file: File, dataBytes: Long) {
        val totalDataLen = dataBytes + 36
        RandomAccessFile(file, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt(totalDataLen.toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1)                          // PCM
            h.putShort(1)                          // mono
            h.putInt(SAMPLE_RATE)
            h.putInt(SAMPLE_RATE * 2)              // byte rate
            h.putShort(2)                          // block align
            h.putShort(16)                         // bits per sample
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(h.array())
        }
    }
}
