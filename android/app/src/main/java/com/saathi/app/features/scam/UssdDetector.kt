package com.saathi.app.features.scam

data class UssdFinding(val code: String, val level: ScamLevel, val reason: String)

/**
 * Finds USSD/MMI codes in a message (SPEC §7.6 F1). Call-forwarding codes are always RED, because
 * dialling one silently diverts the user's calls (and OTP calls) to the scammer.
 */
object UssdDetector {
    private val forwarding = listOf(
        "**004*", "*004*", "**21*", "*21*", "**61*", "**62*", "**67*", "*61*", "*62*", "*67*", "*401*",
    )
    private val harmless = setOf("*#06#", "*#*#4636#*#*")
    private val codeRegex = Regex("(?<![\\w*#])[*#]{1,4}\\d{1,4}[*#][\\d*#+]*")
    private val dialWords = Regex("(?i)\\b(dial|call|press|type|send)\\b|डायल|दबाएं|dabaye|dabao")

    fun codes(text: String): List<String> = codeRegex.findAll(text).map { it.value }.toList()

    /** The most serious USSD finding in [text], or null when there is none. */
    fun detect(text: String, fromContact: Boolean): UssdFinding? =
        codes(text).map { classify(it, text, fromContact) }.maxByOrNull { it.level.ordinal }

    private fun classify(code: String, text: String, fromContact: Boolean): UssdFinding = when {
        forwarding.any { code.startsWith(it) } -> UssdFinding(code, ScamLevel.RED, "ussd_forwarding")
        code in harmless || harmless.any { code.startsWith(it) } -> UssdFinding(code, ScamLevel.INFO, "ussd_info")
        code.startsWith("*99") -> UssdFinding(
            code, if (fromContact) ScamLevel.INFO else ScamLevel.AMBER, "ussd_upi",
        )
        !fromContact && code.contains('*') && code.contains('#') && dialWords.containsMatchIn(text) ->
            UssdFinding(code, ScamLevel.AMBER, "ussd_unknown")
        else -> UssdFinding(code, ScamLevel.INFO, "ussd_info")
    }
}
