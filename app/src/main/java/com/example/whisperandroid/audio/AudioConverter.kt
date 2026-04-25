package com.example.whisperandroid.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 任意の音声 (mp3/m4a/aac/wav etc.) を whisper 入力に適した
 * 16kHz / mono / pcm_s16le WAV へ変換する。
 *
 *   ffmpeg -y -i {input} -ar 16000 -ac 1 -c:a pcm_s16le {temp_wav}
 */
object AudioConverter {

    private const val TAG = "AudioConverter"

    /**
     * @param inputPath 入力音声 (アプリ内のローカルパス)
     * @param outputWav 出力先 (WAV)
     * @return 成功時 outputWav、失敗時は null
     */
    suspend fun convertTo16kMono(inputPath: String, outputWav: File): File? =
        withContext(Dispatchers.IO) {
            // 念のため既存を削除
            if (outputWav.exists()) outputWav.delete()

            val cmd = buildString {
                append("-y ")
                append("-i ")
                append('"').append(inputPath).append('"').append(' ')
                append("-ar 16000 -ac 1 -c:a pcm_s16le ")
                append('"').append(outputWav.absolutePath).append('"')
            }
            Log.i(TAG, "ffmpeg: $cmd")

            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode)) {
                outputWav
            } else {
                Log.e(TAG, "ffmpeg failed: ${session.failStackTrace}")
                null
            }
        }
}
