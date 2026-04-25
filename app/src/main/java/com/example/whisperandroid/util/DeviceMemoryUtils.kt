package com.example.whisperandroid.util

import android.app.ActivityManager
import android.content.Context
import com.example.whisperandroid.data.model.WhisperModel

object DeviceMemoryUtils {

    /** 端末の合計RAMをGB単位で取得。 */
    fun totalRamGB(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1024f / 1024f / 1024f
    }

    /** RAMに対して安全に扱えるモデル一覧。 */
    fun recommendedModels(context: Context): List<WhisperModel> {
        val gb = totalRamGB(context)
        return WhisperModel.values().filter { it.recommendedRamGB <= gb + 0.5f }
    }

    /**
     * 指定モデルが端末RAM的にリスキーかを判定。
     * UIで警告表示するのに使う。
     */
    fun isRisky(context: Context, model: WhisperModel): Boolean =
        totalRamGB(context) < model.recommendedRamGB
}
