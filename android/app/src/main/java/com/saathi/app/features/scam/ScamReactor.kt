package com.saathi.app.features.scam

import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.EventEntity
import com.saathi.app.core.overlay.ScamAlert
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android side of the message scanner: checks unresolved links with the backend (local rules as fallback),
 * stores the risk event, shows the alert and speaks the bundled phrase.
 */
class ScamReactor(private val container: AppContainer) {

    /** Scans [rawText] (never leaves the phone except individual URLs) and returns the final result. */
    suspend fun scanAndCheckLinks(rawText: String, fromContact: Boolean): ScanResult {
        var result = MessageScanner.scan(rawText, fromContact)
        for (url in result.urlsToCheck.take(3)) {
            val verdict = withTimeoutOrNull(15_000) {
                runCatching { container.backend.checkLink(url) }.getOrNull()
            } ?: LocalLinkRules.verdict(url)
            result = MessageScanner.withVerdict(result, verdict.verdict)
        }
        return result
    }

    /** Records and announces a scan result for stored message [msgId]. INFO results are only logged. */
    suspend fun react(msgId: Long?, result: ScanResult) {
        if (result.level < ScamLevel.INFO) return
        container.db.events().insert(EventEntity(
            type = "message", level = result.level.wire, reasons = result.reasons.joinToString(","),
            sourceMsgId = msgId, ts = System.currentTimeMillis(),
        ))
        if (result.level < ScamLevel.AMBER) return
        val style = container.settings.current.replyStyle
        val title = if (result.level == ScamLevel.RED) {
            tr(style, "Dangerous message", "खतरनाक संदेश", "Khatarnaak message")
        } else {
            tr(style, "Be careful with this message", "इस संदेश से सावधान रहें", "Is message se savdhaan rahein")
        }
        val key = result.phraseKey ?: "scam_amber_message"
        val alert = ScamAlert(result.level.toAlertLevel(), title, ReasonText.join(result.reasons, style), key)
        withContext(Dispatchers.Main) { container.alerts.show(alert) }
        container.scope.launch { container.speaker.sayPhrase(key, style) }
    }
}
