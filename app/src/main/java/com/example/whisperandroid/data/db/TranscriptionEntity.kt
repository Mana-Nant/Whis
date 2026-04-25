package com.example.whisperandroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 元のファイル名 (表示用) */
    @ColumnInfo(name = "source_name") val sourceName: String,

    /** 元ファイルのURI文字列 (SAF) */
    @ColumnInfo(name = "source_uri") val sourceUri: String?,

    /** 使用モデルのファイル名 */
    @ColumnInfo(name = "model_name") val modelName: String,

    /** 文字起こし本文 */
    @ColumnInfo(name = "text") val text: String,

    /** 秒単位の音声長 (不明時は -1) */
    @ColumnInfo(name = "duration_sec") val durationSec: Int = -1,

    /** 推論にかかったミリ秒 */
    @ColumnInfo(name = "inference_ms") val inferenceMs: Long = 0,

    /** 作成日時 (epoch millis) */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
