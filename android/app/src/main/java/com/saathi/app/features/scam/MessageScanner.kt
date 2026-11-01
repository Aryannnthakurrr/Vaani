package com.saathi.app.features.scam

/**
 * Result of scanning one message. [urlsToCheck] are links the local rules could not settle; the
 * [ScamReactor] sends them to the backend link checker. [phraseKey] is the bundled phrase to speak.
 */
data class ScanResult(
    val level: ScamLevel,
    val reasons: List<String>,
    val phraseKey: String?,
    val urls: List<String>,
    val urlsToCheck: List<String>,
    /** The call-forwarding code the message asks to dial ("*401*98765…"), shown in the alert. */
    val forwardingCode: String? = null,
)

/** Pure, on-device message scanner (SPEC §7.6 F1). Runs on the raw text; nothing here touches the network. */
object MessageScanner {

    fun scan(text: String, fromContact: Boolean): ScanResult {
        var level = ScamLevel.NONE
        val reasons = mutableListOf<String>()
        var phrase: String? = null

        fun raise(to: ScamLevel, reason: String, key: String?) {
            if (reason !in reasons) reasons += reason
            if (to.ordinal > level.ordinal) {
                level = to
                if (key != null) phrase = key
            } else if (phrase == null && key != null && to == level) {
                phrase = key
            }
        }

        val ussd = UssdDetector.detect(text, fromContact)
        ussd?.let { f ->
            when (f.level) {
                ScamLevel.RED -> raise(ScamLevel.RED, f.reason, "scam_red_ussd")
                ScamLevel.AMBER -> raise(ScamLevel.AMBER, f.reason, "scam_amber_message")
                else -> Unit
            }
        }

        val urls = UrlExtractor.extract(text)
        val toCheck = mutableListOf<String>()
        for (url in urls) {
            // "/downloads" on incometax.gov.in is a form, not an app: on official sites only a real .apk counts.
            if (UrlExtractor.isApkLink(url) && (UrlExtractor.isApkFile(url) || !LocalLinkRules.isOfficial(UrlExtractor.host(url)))) {
                raise(ScamLevel.RED, "apk_link", "scam_red_apk")
                continue
            }
            val local = LocalLinkRules.signals(url)
            val worst = local.fold(ScamLevel.NONE) { acc, s -> acc.max(s.second) }
            when (worst) {
                ScamLevel.RED -> raise(ScamLevel.RED, "dangerous_link", "scam_red_link")
                ScamLevel.AMBER -> {
                    raise(ScamLevel.AMBER, "suspicious_link", "scam_amber_message")
                    toCheck += url
                }
                else -> if (!LocalLinkRules.isOfficial(UrlExtractor.host(url))) toCheck += url
            }
        }

        val textHits = ScamHeuristics.reasons(text)
        if (textHits.isNotEmpty()) {
            val hitLevel = when {
                fromContact -> ScamLevel.INFO
                urls.isNotEmpty() || textHits.size >= 2 -> ScamLevel.RED
                else -> ScamLevel.AMBER
            }
            val key = if (hitLevel == ScamLevel.RED) "scam_red_link" else "scam_amber_message"
            textHits.forEach { raise(hitLevel, it, if (hitLevel == ScamLevel.INFO) null else key) }
        }

        return ScanResult(level, reasons, if (level >= ScamLevel.AMBER) phrase else null, urls, toCheck,
            forwardingCode = ussd?.takeIf { it.reason == "ussd_forwarding" }?.code)
    }

    /** Upgrades a scan with a backend (or fallback) link verdict. */
    fun withVerdict(result: ScanResult, verdict: String): ScanResult = when (verdict) {
        "dangerous" -> result.copy(
            level = ScamLevel.RED,
            reasons = (result.reasons + "dangerous_link").distinct(),
            phraseKey = if (result.level == ScamLevel.RED && result.phraseKey != null) result.phraseKey else "scam_red_link",
        )
        "suspicious" -> if (result.level >= ScamLevel.AMBER) result else result.copy(
            level = ScamLevel.AMBER,
            reasons = (result.reasons + "suspicious_link").distinct(),
            phraseKey = "scam_amber_message",
        )
        else -> result
    }
}
