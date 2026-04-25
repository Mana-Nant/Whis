package com.example.whisperandroid

import android.app.Application
import com.example.whisperandroid.data.db.AppDatabase
import com.example.whisperandroid.data.model.ModelRepository
import com.example.whisperandroid.data.prefs.AppPreferences

/**
 * アプリ全体で共有するシングルトン群の保持場所。
 * フルDIフレームワーク（Hilt）を使わず、Application経由で取得する簡素な構成。
 */
class WhisperApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var preferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        modelRepository = ModelRepository(this)
        preferences = AppPreferences(this)
    }
}
