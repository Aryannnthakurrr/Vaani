package com.saathi.app.features.inbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.saathi.app.SaathiApp

/** Receives SMS, joins multipart bodies per sender, and hands them to the ingestor. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val bySender = parts.groupBy { it.originatingAddress ?: "unknown" }
        launchAsync {
            for ((sender, msgs) in bySender) {
                val body = msgs.joinToString("") { it.messageBody ?: "" }
                val ts = msgs.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                SaathiApp.container.ingestor.ingest("sms", sender, body, ts)
            }
        }
    }
}
