package com.saathi.app.features.scam

import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.EventEntity
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.overlay.ScamAlert
import com.saathi.app.core.voice.tr
import com.saathi.app.features.profile.ProfileManager
import com.saathi.app.util.Packages
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

    /**
     * Records and announces a scan result for stored message [msgId], received from [sender] through [sourcePkg].
     * INFO results are only logged.
     */
    suspend fun react(msgId: Long?, result: ScanResult, sourcePkg: String? = null, sender: String? = null) {
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
        // What to do, not just what's wrong.
        val advice = tr(style, "Don't open the link, don't reply and don't share any OTP.",
            "लिंक मत खोलिए, जवाब मत दीजिए और कोई ओटीपी मत बताइए।",
            "Link mat kholiye, jawab mat dijiye aur koi OTP mat bataiye.")
        val body = listOf(ReasonText.join(result.reasons, style), advice).filter { it.isNotBlank() }.joinToString("\n")
        val alert = ScamAlert(result.level.toAlertLevel(), title, body, key, from = fromLine(sourcePkg, sender, style))
        withContext(Dispatchers.Main) { container.alerts.show(alert) }
        container.scope.launch { container.speaker.sayPhrase(key, style) }
    }

    /** "From Ramesh on WhatsApp"; just the sender when the source isn't a real app (the demo profile). */
    private fun fromLine(sourcePkg: String?, sender: String?, style: ReplyStyle): String? {
        val who = sender?.takeIf { it.isNotBlank() }
        val app = sourcePkg?.takeIf { it != ProfileManager.DEMO_SOURCE }?.let { Packages.label(container.context, it) }
            ?.takeIf { it != who }
        return when {
            who != null && app != null -> tr(style, "From $who on $app", "$app पर $who से", "$app par $who se")
            who != null -> tr(style, "From $who", "$who से", "$who se")
            app != null -> tr(style, "On $app", "$app पर", "$app par")
            else -> null
        }
    }
}
