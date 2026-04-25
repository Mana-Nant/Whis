package com.example.whisperandroid.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * ユーザー設定の永続化。SharedPreferencesベース。
 */
class AppPreferences(context: Context) {

    private val sp = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)

    /** 現在選択中のモデルファイル名 (例: ggml-base-q5_0.bin) */
    var currentModelFileName: String?
        get() = sp.getString(KEY_CURRENT_MODEL, null)
        set(value) = sp.edit { putString(KEY_CURRENT_MODEL, value) }

    /** 自動書き出しON/OFF */
    var autoExportEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_EXPORT, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_EXPORT, value) }

    /** 既定の書き出し先ディレクトリのSAF tree URI */
    var defaultExportTreeUri: String?
        get() = sp.getString(KEY_EXPORT_TREE_URI, null)
        set(value) = sp.edit { putString(KEY_EXPORT_TREE_URI, value) }

    /** 推論スレッド数（0=自動） */
    var inferenceThreads: Int
        get() = sp.getInt(KEY_THREADS, 0)
        set(value) = sp.edit { putInt(KEY_THREADS, value) }

    companion object {
        private const val KEY_CURRENT_MODEL = "current_model"
        private const val KEY_AUTO_EXPORT = "auto_export"
        private const val KEY_EXPORT_TREE_URI = "export_tree_uri"
        private const val KEY_THREADS = "threads"
    }
}
