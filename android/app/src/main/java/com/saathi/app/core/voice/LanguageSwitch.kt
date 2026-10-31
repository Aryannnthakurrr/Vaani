package com.saathi.app.core.voice

/**
 * Spoken requests to change Saathi's language for good ("mujhe English nahi aati", "sirf Hindi mein bolo",
 * "please speak English"). Returns the language code to save ("hi" or "en"), or null when the sentence isn't one.
 * Pure, so it is JVM-tested.
 */
object LanguageSwitch {
    private val TO_HINDI = listOf(
        Regex("(?i)english\\s+(nahi|nahin|nai|na)\\s+(aati|aata|aaati|samajh|samjh|aati hai|pata)"),
        Regex("(?i)\\b(bas|sirf|keval|only)\\s+hindi\\b"),
        Regex("(?i)\\bhindi\\s+(mein|me|main)\\s+(bolo|boliye|baat|samjhao|batao|likho)"),
        Regex("(?i)\\b(i\\s+(don'?t|do not|can'?t|cannot)\\s+(understand|speak|read)\\s+english)\\b"),
        Regex("(?i)\\b(speak|talk|reply)\\s+(in\\s+)?hindi\\b"),
        Regex("अंग्रेज़ी\\s*नहीं|अंग्रेजी\\s*नहीं|इंग्लिश\\s*नहीं|सिर्फ़?\\s*हिंदी|हिंदी\\s*में\\s*(बोलो|बोलिए|बात)"),
    )
    private val TO_ENGLISH = listOf(
        Regex("(?i)hindi\\s+(nahi|nahin|nai|na)\\s+(aati|aata|samajh|samjh)"),
        Regex("(?i)\\b(bas|sirf|only)\\s+english\\b"),
        Regex("(?i)\\b(speak|talk|reply)\\s+(in\\s+)?english\\b"),
        Regex("(?i)\\benglish\\s+(mein|me|main)\\s+(bolo|boliye|baat|batao)"),
        Regex("(?i)\\bi\\s+(don'?t|do not|can'?t|cannot)\\s+(understand|speak)\\s+hindi\\b"),
    )

    fun detect(utterance: String?): String? {
        val t = utterance?.trim().orEmpty()
        if (t.isEmpty()) return null
        // English-side phrases first: "Hindi nahi aati, English mein bolo" must switch to English.
        if (TO_ENGLISH.any { it.containsMatchIn(t) }) return "en"
        if (TO_HINDI.any { it.containsMatchIn(t) }) return "hi"
        return null
    }

    /** What Saathi says after switching, in the new language. */
    fun confirmation(code: String): String =
        if (code == "hi") "ठीक है, अब से मैं सिर्फ़ हिंदी में बात करूँगा।" else "Okay, from now on I will speak in English."
}
