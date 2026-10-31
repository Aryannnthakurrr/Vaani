package com.saathi.app.core.overlay

import com.saathi.app.core.Notifier

enum class AlertLevel { INFO, AMBER, RED }

/**
 * A safety alert. [phraseKey] names the bundled phrase that is spoken (never fetched from the network).
 * [inCall] routes speech to the earpiece and offers the "Hang up" button.
 */
data class ScamAlert(
    val level: AlertLevel,
    val title: String,
    val body: String,
    val phraseKey: String,
    val inCall: Boolean = false,
)

/**
 * Shows alerts: RED uses the full-screen [AlertOverlay] when the overlay service is running, with a
 * full-screen-intent notification as the fallback; AMBER posts a heads-up notification.
 * Speech and vibration are the caller's job (CallGuard / MessageScanner) so they can pick in-call audio.
 */
class AlertRouter(private val notifier: Notifier) {

    fun show(alert: ScamAlert) {
        val shownAsOverlay = alert.level == AlertLevel.RED && OverlayService.controller?.showAlert(alert) == true
        if (!shownAsOverlay) {
            notifier.post(
                Notifier.CH_ALERTS, alert.title.hashCode(), alert.title, alert.body,
                tap = notifier.openAppIntent("alerts"), fullScreen = alert.level == AlertLevel.RED,
            )
        }
    }
}
