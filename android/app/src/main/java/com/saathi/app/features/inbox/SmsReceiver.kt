package com.saathi.app.features.inbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.saathi.app.SaathiApp
import kotlinx.coroutines.launch

/** Receives SMS, joins multipart bodies per sender, and hands them to the ingestor. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val bySender = parts.groupBy { it.originatingAddress ?: "unknown" }
        // Not launchAsync: its 9 s cap could cancel ingest mid link-check (up to 15 s on a slow network), after the
        // message was stored but before the scam alert. The foreground overlay service keeps the process alive.
        SaathiApp.container.scope.launch {
            for ((sender, msgs) in bySender) {
                val body = msgs.joinToString("") { it.messageBody ?: "" }
                val ts = msgs.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                SaathiApp.container.ingestor.ingest("sms", sender, body, ts)
            }
        }
    }
}
