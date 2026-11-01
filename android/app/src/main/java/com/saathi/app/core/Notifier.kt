package com.saathi.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.saathi.app.MainActivity
import com.saathi.app.R
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr

/** Notification channels and helpers. Every notification text is also spoken by its caller. */
class Notifier(private val context: Context) {

    /**
     * Creates the channels, or renames them: calling again with another [style] only updates the names shown in the
     * phone's notification settings (Android keeps the user's sound and importance choices).
     */
    fun createChannels(style: ReplyStyle = ReplyStyle.EN) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_ASSISTANT, tr(style, "Vaani assistant", "वाणी सहायक", "Vaani assistant"), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_ALERTS, tr(style, "Scam alerts", "धोखे की चेतावनी", "Scam alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
            },
            NotificationChannel(CH_REMINDERS, tr(style, "Reminders", "रिमाइंडर", "Reminders"), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_WELFARE, tr(style, "Daily check-in", "रोज़ की जाँच", "Daily check-in"), NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    fun openAppIntent(route: String? = null, extraAction: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
            extraAction?.let { action = it }
        }
        return PendingIntent.getActivity(context, (route ?: "home").hashCode() + (extraAction?.hashCode() ?: 0),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(
        channel: String, id: Int, title: String, text: String,
        tap: PendingIntent = openAppIntent(), fullScreen: Boolean = false,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (fullScreen) setFullScreenIntent(tap, true) }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CH_ASSISTANT = "assistant"
        const val CH_ALERTS = "alerts"
        const val CH_REMINDERS = "reminders"
        const val CH_WELFARE = "welfare"
    }
}
