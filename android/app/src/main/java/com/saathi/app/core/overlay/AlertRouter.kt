package com.saathi.app.core.overlay

import com.saathi.app.core.Notifier

enum class AlertLevel { INFO, AMBER, RED }

/**
 * A safety alert. [phraseKey] names the bundled phrase that is spoken (never fetched from the network).
 * [inCall] routes speech to the earpiece and offers the "Hang up" button (and covers the whole screen; a message
 * alert is a card that leaves the app visible). [from] says who it came from and where ("From Ramesh on WhatsApp").
 */
data class ScamAlert(
    val level: AlertLevel,
    val title: String,
    val body: String,
    val phraseKey: String,
    val inCall: Boolean = false,
    val from: String? = null,
)

/**
 * Shows alerts: RED uses the full-screen [AlertOverlay] when the overlay service is running, with a
 * full-screen-intent notification as the fallback; AMBER posts a heads-up notification.
 * Speech and vibration are the caller's job (CallGuard / MessageScanner) so they can pick in-call audio.
 */
class AlertRouter(private val notifier: Notifier) {

    fun show(alert: ScamAlert) {
        val shownAsOverlay = alert.level == AlertLevel.RED && OverlayService.controller?.showAlert(alert) == true
        if (!shownAsOverlay) notify(alert)
    }

    /** The notification form; also the fallback when the overlay window can't be added after all. */
    fun notify(alert: ScamAlert) {
        notifier.post(
            Notifier.CH_ALERTS, alert.title.hashCode(), alert.title, listOfNotNull(alert.from, alert.body).joinToString("\n"),
            tap = notifier.openAppIntent("alerts"), fullScreen = alert.level == AlertLevel.RED,
        )
    }
}
