package com.example.whisperandroid.data.model

/**
 * whisper.cpp で使える標準モデル一覧。
 * q5_0 は量子化版で最も実用的。q8_0 は精度重視。
 * ファイル名は Hugging Face の ggml-*.bin に合わせる。
 */
enum class WhisperModel(
    val displayName: String,
    val fileName: String,
    val approxSizeMB: Int,
    val recommendedRamGB: Int
) {
    TINY_Q5   ("tiny (q5_0, ja OK 軽量)",    "ggml-tiny-q5_0.bin",    31,  2),
    BASE_Q5   ("base (q5_0, バランス)",       "ggml-base-q5_0.bin",    58,  3),
    SMALL_Q5  ("small (q5_0, 高品質)",        "ggml-small-q5_0.bin",   190, 4),
    MEDIUM_Q5 ("medium (q5_0, 高負荷)",       "ggml-medium-q5_0.bin",  539, 6),
    LARGE_V3_Q5("large-v3 (q5_0, 最高精度)",  "ggml-large-v3-q5_0.bin", 1080, 8);

    /** Hugging Faceから直接DLできるURL。 */
    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun fromFileName(name: String): WhisperModel? =
            values().firstOrNull { it.fileName == name }
    }
}
