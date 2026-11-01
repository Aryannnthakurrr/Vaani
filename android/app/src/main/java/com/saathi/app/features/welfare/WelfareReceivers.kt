package com.saathi.app.features.welfare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saathi.app.SaathiApp
import com.saathi.app.core.Notifier
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.features.inbox.launchAsync
import java.time.ZonedDateTime

/** Daily on-device prompt: "Tap here to tell your family you're okay", only if the phone sat unused overnight. */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        launchAsync {
            val c = SaathiApp.container
            c.welfare.scheduleDailyPrompt()
            val s = c.settings.current
            if (!WelfareRules.needsCheckIn(s.lastUnlockTs, ZonedDateTime.now())) return@launchAsync
            val style = s.replyStyle
            val text = c.phrases.text("welfare_prompt", style)
            c.notifier.post(Notifier.CH_WELFARE, 11_30, "Vaani", text,
                tap = c.notifier.openAppIntent(null, ACTION_CHECK_IN), fullScreen = true)
            c.speaker.sayPhrase("welfare_prompt", style)
        }
    }

    companion object {
        const val ACTION_CHECK_IN = "com.saathi.app.CHECK_IN"
    }
}

/** ACTION_USER_PRESENT can't be declared in the manifest; OverlayService registers this at runtime. */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) SaathiApp.container.welfare.onUnlock()
    }
}

/** Restores the overlay, alarms and reminders after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { OverlayService.start(context) }
        val c = SaathiApp.container
        c.welfare.scheduleDailyPrompt()
        c.digest.scheduleMorningDigest()
        launchAsync { c.reminders.rescheduleAll(c.db.reminders().pending()) }
    }
}
