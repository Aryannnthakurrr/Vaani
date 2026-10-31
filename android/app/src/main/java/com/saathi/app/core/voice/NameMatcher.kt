package com.saathi.app.core.voice

/**
 * Fixes names that speech recognition got nearly right ("Keshu" → "Keshav") against names the phone already knows:
 * its contacts, the family contacts and the user's own name. Runs on the phone, so the contact list never leaves it.
 * Pure, so it is JVM-tested.
 */
object NameMatcher {
    /** Words a name sits after: "send it to Keshu", "Keshu ko bhejo" (before), "call Keshu". */
    private val CUES = setOf("to", "for", "call", "message", "with", "ko", "ke", "ki", "naam", "name", "is", "hai")
    private val WORD = Regex("[A-Za-z]+")

    /** The closest known first name to [word], or null when nothing is clearly the same name. */
    fun closest(word: String, names: Collection<String>): String? {
        if (word.length < 3) return null
        val w = word.lowercase()
        return names.asSequence()
            .mapNotNull { full -> full.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length >= 3 } }
            .distinct()
            .map { it to score(w, it.lowercase()) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    /** A whole answer to "What is the patient's name?": every word is fixed, and a known full name wins outright. */
    fun correctName(answer: String, names: Collection<String>): String {
        val trimmed = answer.trim().trimEnd('.', '!', '?')
        names.firstOrNull { score(trimmed.lowercase(), it.lowercase()) >= FULL_NAME_SCORE }?.let { return it }
        return WORD.replace(trimmed) { m -> closest(m.value, names) ?: m.value }
    }

    /** A request ("send this photo to Keshu"): only words next to a cue are treated as names. */
    fun correctInSentence(text: String, names: Collection<String>): String {
        if (names.isEmpty()) return text
        val words = WORD.findAll(text).toList()
        val out = StringBuilder(text)
        // Right to left, so earlier offsets stay valid after a replacement of a different length.
        for (i in words.indices.reversed()) {
            val m = words[i]
            val before = words.getOrNull(i - 1)?.value?.lowercase()
            val after = words.getOrNull(i + 1)?.value?.lowercase()
            if (m.value.lowercase() in CUES || (before !in CUES && after !in setOf("ko", "ke", "ki"))) continue
            val fix = closest(m.value, names) ?: continue
            if (!fix.equals(m.value, ignoreCase = true)) out.replace(m.range.first, m.range.last + 1, fix)
        }
        return out.toString()
    }

    /**
     * 0..1: 1 − edit distance / longer length, for words sharing their first three letters. A looser match
     * (under 0.75) must share four, so "Keshu" → "Keshav" but not "Kesar" (saffron) → "Keshav".
     */
    internal fun score(a: String, b: String): Double {
        if (a.length < 3 || b.length < 3 || a.take(3) != b.take(3)) return 0.0
        val s = 1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length)
        return if (s < 0.75 && a.take(4) != b.take(4)) 0.0 else s
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private const val MIN_SCORE = 0.6
    private const val FULL_NAME_SCORE = 0.8
}
