package com.example.whisperandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.whisperandroid.MainActivity
import com.example.whisperandroid.R

object NotificationHelper {

    const val CHANNEL_ID = "whisper_transcription"
    const val CHANNEL_NAME = "文字起こし進捗"
    const val NOTIF_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "音声変換・推論の進捗を表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(
        context: Context,
        title: String,
        content: String,
        progress: Int? = null,
        indeterminate: Boolean = false,
        ongoing: Boolean = true
    ): Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

        when {
            indeterminate -> b.setProgress(0, 0, true)
            progress != null -> b.setProgress(100, progress.coerceIn(0, 100), false)
        }
        return b.build()
    }
}
