package com.saathi.app.features.inbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saathi.app.SaathiApp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.Notifier
import com.saathi.app.core.data.MessageEntity
import com.saathi.app.core.net.DigestItem
import com.saathi.app.core.net.DigestRequest
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Builds the daily digest from stored (redacted) messages and speaks it (SPEC §7.4). */
class DigestRepository(private val container: AppContainer) {

    suspend fun items(): List<DigestItem> {
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        return toItems(container.db.messages().since(since))
    }

    suspend fun spokenText(): String {
        val s = container.settings.current
        val items = items()
        val request = DigestRequest(items, s.replyStyle.wire, s.userName.ifBlank { null }, LocalDate.now().toString())
        return withTimeoutOrNull(8_000) { runCatching { container.backend.digest(request) }.getOrNull() }
            ?: LocalDigestWriter.write(items, s.replyStyle, s.userName.ifBlank { null })
    }

    suspend fun speak(): String {
        val text = spokenText()
        container.speaker.say(text, container.settings.current.replyStyle)
        return text
    }

    /** Daily 09:00 digest alarm (inexact; a few minutes of drift is fine for a morning summary). */
    fun scheduleMorningDigest() {
        val ctx = container.context
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now()
        var next = now.with(LocalTime.of(9, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(ctx, 9_000, Intent(ctx, DigestAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pi)
    }

    companion object {
        private val skipped = setOf("promo", "personal", "other")

        /** Pure mapping used by the screen and the spoken digest; OTP and contact messages are excluded. */
        fun toItems(rows: List<MessageEntity>): List<DigestItem> = rows
            .filter { !it.isOtp && !it.fromContact && it.category !in skipped }
            .map { DigestItem(it.category, it.merchant, it.amount, it.dueDate, it.summary ?: it.redactedText.take(90)) }
            .distinctBy { listOf(it.category, it.merchant, it.amount, it.dueDate) }
    }
}

/** Fires at 09:00: posts the digest as a notification and reads it aloud. */
class DigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        launchAsync {
            val c = SaathiApp.container
            val text = c.digest.spokenText()
            val style = c.settings.current.replyStyle
            c.notifier.post(Notifier.CH_REMINDERS, 9_000, tr(style, "Your morning updates", "आज की जानकारी", "Aaj ki updates"),
                text, tap = c.notifier.openAppIntent("digest"))
            c.speaker.say(text, style)
        }
    }
}
