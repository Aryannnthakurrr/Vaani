package com.saathi.app.features.welfare

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saathi.app.SaathiApp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.WelfareConfig
import com.saathi.app.core.voice.tr
import com.saathi.app.features.inbox.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Welfare check (SPEC §7.5). The phone reports unlocks as heartbeats; the backend decides whether
 * to alert family, so the check still works when the phone is dead. The 11:30 on-device prompt is extra.
 */
class WelfareManager(private val container: AppContainer) {
    private val context: Context get() = container.context

    fun onUnlock() {
        container.scope.launch {
            val now = System.currentTimeMillis()
            container.settings.setLastUnlock(now)
            if (WelfareRules.shouldSendHeartbeat(container.settings.current.lastHeartbeatTs, now)) {
                container.settings.setLastHeartbeat(now)
                enqueueHeartbeat(now)
            }
        }
    }

    /** Queued heartbeat: waits for network and retries with backoff, so offline unlocks still count. */
    fun enqueueHeartbeat(ts: Long) {
        val req = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInputData(workDataOf(HeartbeatWorker.KEY_TS to ts))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("heartbeat", ExistingWorkPolicy.REPLACE, req)
    }

    fun scheduleDailyPrompt() {
        val at = WelfareRules.nextAt(container.settings.current.welfarePromptTime, ZonedDateTime.now())
        val pi = PendingIntent.getBroadcast(context, 11_30, Intent(context, CheckInReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ReminderScheduler.setAlarm(context, context.getSystemService(AlarmManager::class.java), at.toInstant().toEpochMilli(), pi)
    }

    /** "I'm okay": sends a heartbeat now, queueing it when offline. */
    suspend fun checkIn() {
        val now = System.currentTimeMillis()
        container.settings.setLastUnlock(now)
        container.settings.setLastHeartbeat(now)
        runCatching { container.backend.heartbeat(now) }.onFailure { enqueueHeartbeat(now) }
    }

    suspend fun pushConfig() {
        val s = container.settings.current
        container.backend.welfareConfig(WelfareConfig(s.deviceId, s.userName.ifBlank { "Saathi user" }, s.contacts, s.welfareCheckTime))
    }

    /** Triggers the backend alert path now; returns a sentence for the Settings screen. */
    suspend fun sendTestAlert(): String {
        val style = container.settings.current.replyStyle
        return runCatching {
            pushConfig()
            val r = container.backend.welfareTest()
            when {
                r.sent.isNotEmpty() -> tr(style, "Test alert sent to ${r.sent.size} family member(s) on Telegram.",
                    "टेस्ट अलर्ट ${r.sent.size} परिवार वालों को टेलीग्राम पर भेजा गया।",
                    "Test alert ${r.sent.size} family members ko Telegram par bheja gaya.")
                else -> tr(style, "Test alert logged on the server. Add a Telegram bot token to send it for real.",
                    "टेस्ट अलर्ट सर्वर पर दर्ज हुआ। असली भेजने के लिए टेलीग्राम बॉट टोकन जोड़िए।",
                    "Test alert server par log hua. Asli bhejne ke liye Telegram bot token jodiye.") +
                    (r.text?.let { "\n$it" } ?: "")
            }
        }.getOrElse {
            tr(style, "Could not reach the Saathi server. Check the backend address in Settings.",
                "साथी सर्वर से जुड़ नहीं पाए। सेटिंग्स में बैकएंड पता देखिए।",
                "Saathi server se connect nahi ho paya. Settings mein backend address dekhiye.")
        }
    }
}

class HeartbeatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ts = inputData.getLong(KEY_TS, System.currentTimeMillis())
        return runCatching { SaathiApp.container.backend.heartbeat(ts) }
            .fold({ Result.success() }, { if (runAttemptCount < 8) Result.retry() else Result.failure() })
    }

    companion object {
        const val KEY_TS = "ts"
    }
}
