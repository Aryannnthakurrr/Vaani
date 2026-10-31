package com.saathi.app.features.inbox

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.saathi.app.SaathiApp
import com.saathi.app.util.Packages
import kotlinx.coroutines.launch

/**
 * Reads notifications from messaging, mail and bank apps into the inbox, and spots screen-sharing
 * notifications for CallGuard. Google Messages is skipped while SmsReceiver already sees the SMS.
 */
class NotifListener : NotificationListenerService() {

    private val screenShare = Regex(
        "(?i)(start(ed)?|now) (casting|sharing|recording)|screen (share|sharing|cast|recording)|sharing your screen|" +
            "स्क्रीन शेयर|स्क्रीन (रिकॉर्ड|कास्ट)",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == Packages.SELF) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val container = SaathiApp.container

        if (screenShare.containsMatchIn("$title $text") || pkg in Packages.REMOTE_ACCESS_APPS) {
            container.callGuard.onScreenShareSuspected()
        }

        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (pkg !in Packages.INBOX_SOURCES && pkg !in Packages.MONEY_APPS) return
        if (pkg == Packages.GOOGLE_MESSAGES && smsReceiverActive()) return
        if (text.isBlank()) return

        val sender = title.ifBlank { Packages.displayName(pkg) }
        container.scope.launch { container.ingestor.ingest(pkg, sender, text, sbn.postTime) }
    }

    private fun smsReceiverActive() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
