package com.example.whisperandroid.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 任意の音声 (mp3/m4a/aac/wav/flac/ogg) を whisper 入力に適した
 * 16kHz / mono / pcm_s16le WAV へ変換する。
 *
 * 元設計では ffmpeg-kit を利用していたが、本家が2025年1月に廃止され
 * バイナリも削除されたため、Android標準APIに置き換え。外部依存ゼロで動く。
 *
 *   1) MediaExtractor で音声トラック選択
 *   2) MediaCodec で PCM 16-bit デコード
 *   3) チャンネルダウンミックス (ステレオ等 → モノ)
 *   4) 線形補間でリサンプリング → 16kHz
 *   5) RIFF/WAV ヘッダ付与して保存
 */
object AudioConverter {

    private const val TAG = "AudioConverter"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    suspend fun convertTo16kMono(inputPath: String, outputWav: File): File? =
        withContext(Dispatchers.IO) {
            runCatching { doConvert(inputPath, outputWav) }
                .onFailure { Log.e(TAG, "convert failed: $inputPath", it) }
                .map { outputWav }
                .getOrNull()
        }

    private fun doConvert(inputPath: String, outputWav: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            } ?: error("音声トラックが見つかりません")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "input: $mime, ${srcRate}Hz, ${channels}ch")

            val pcmMono = decodeToMonoPcm(extractor, format, channels)
            Log.i(TAG, "decoded: ${pcmMono.size} bytes")

            val resampled = if (srcRate == TARGET_SAMPLE_RATE) pcmMono
                else resamplePcm16(pcmMono, srcRate, TARGET_SAMPLE_RATE)

            writeWav(outputWav, resampled)
            Log.i(TAG, "wav: ${outputWav.length()} bytes")
        } finally {
            extractor.release()
        }
    }

    private fun decodeToMonoPcm(
        extractor: MediaExtractor,
        format: MediaFormat,
        channels: Int
    ): ByteArray {
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val src = ByteArray(info.size).also { outBuf.get(it) }

                        if (channels == 1) {
                            out.write(src)
                        } else {
                            // ステレオ等 → モノ (16-bit LE)
                            val sb = ByteBuffer.wrap(src)
                                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = sb.remaining() / channels
                            val mono = ByteArray(frames * 2)
                            val mb = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(frames) {
                                var sum = 0
                                repeat(channels) { sum += sb.get().toInt() }
                                mb.putShort((sum / channels).toShort())
                            }
                            out.write(mono)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return out.toByteArray()
    }

    /** 線形補間リサンプリング (16-bit signed PCM, mono) */
    private fun resamplePcm16(input: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcSamples = input.size / 2
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt()
        val ratio = srcRate.toDouble() / dstRate

        val src = ShortArray(srcSamples).also {
            ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
        val outBytes = ByteArray(dstSamples * 2)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dstSamples) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx
            val a = src[idx].toInt()
            val b = if (idx + 1 < srcSamples) src[idx + 1].toInt() else a
            val v = (a + (b - a) * frac).toInt()
            outBuf.putShort(
                v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            )
        }
        return outBytes
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)                          // PCM
            header.putShort(1)                          // mono
            header.putInt(TARGET_SAMPLE_RATE)
            header.putInt(TARGET_SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            fos.write(header.array())
            fos.write(pcm)
        }
    }
}
