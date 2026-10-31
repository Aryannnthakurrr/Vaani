package com.saathi.app.core.voice

import com.saathi.app.core.net.ReplyStyle

/**
 * Classifies one utterance as English, Hindi (Devanagari) or Hinglish (Hindi in Latin script, often mixed
 * with English nouns). Mirrors backend `language_style.py`; used when the backend is unreachable.
 */
object LanguageStyle {
    /** Common Hindi function words and verbs as they are typed/transcribed in Latin script. */
    private val HINDI_LATIN = setOf(
        "hai", "hain", "ho", "kar", "karo", "kardo", "karna", "karni", "kijiye", "ke", "ki", "ka", "ko", "se", "mein",
        "mujhe", "mera", "meri", "mere", "aap", "aapka", "tum", "jaana", "jana", "jaane", "chahiye", "chahta", "chahti",
        "liye", "nahi", "nahin", "haan", "han", "kya", "kaise", "kahan", "kab", "abhi", "bhai", "beta", "theek", "thik",
        "bolo", "batao", "dikhao", "bhejo", "bhejni", "bhejna", "wala", "wali", "accha", "acha", "ji", "par", "aur",
        "ek", "do", "yeh", "ye", "woh", "wo", "hum", "humko", "mujhko", "dena", "de", "do", "lo", "lena", "ruko", "mat",
        "chalo", "gaya", "gayi", "raha", "rahi", "tha", "thi", "kitna", "kitne", "paisa", "dawai", "aaj", "kal",
    )

    fun detect(text: String, languageCode: String? = null): ReplyStyle {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return fromCode(languageCode)
        val devanagari = letters.count { it in 'ऀ'..'ॿ' }
        if (devanagari.toDouble() / letters.length > 0.5) return ReplyStyle.HI
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return fromCode(languageCode)
        val hindiWords = words.count { it in HINDI_LATIN }
        val ratio = hindiWords.toDouble() / words.size
        return when {
            hindiWords >= 2 || ratio >= 0.25 -> ReplyStyle.HINGLISH
            languageCode?.startsWith("hi") == true && hindiWords >= 1 -> ReplyStyle.HINGLISH
            else -> ReplyStyle.EN
        }
    }

    private fun fromCode(code: String?) = if (code?.startsWith("hi") == true) ReplyStyle.HI else ReplyStyle.EN
}
