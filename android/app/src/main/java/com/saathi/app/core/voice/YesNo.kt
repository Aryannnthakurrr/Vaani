package com.saathi.app.core.voice

enum class Answer { YES, NO, UNCLEAR }

/** Local yes/no recognition for the confirm gate (SPEC §6.6). Runs on the transcript, never in the cloud. */
object YesNo {
    private val NO = listOf(
        "mat karo", "mat kijiye", "rehne do", "rahne do", "nahi", "nahin", "nai", "no", "na", "ruko", "cancel", "stop",
        "don't", "dont", "नहीं", "ना", "मत करो", "रुको", "रहने दो", "कैंसल",
    )
    private val YES = listOf(
        "haan ji", "haan", "han", "ha", "yes", "yeah", "yep", "theek hai", "thik hai", "kar do", "kardo", "book karo",
        "ok", "okay", "sure", "ji", "done", "ho gaya", "हाँ", "हां", "जी", "ठीक है", "कर दो", "बुक करो", "हो गया",
    )

    fun parse(transcript: String?): Answer {
        val t = " " + (transcript ?: "").lowercase().replace(Regex("[^\\p{L}\\p{M}' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank()) return Answer.UNCLEAR
        // "No" wins: "haan, nahi" or "ok mat karo" must never become a yes.
        if (NO.any { t.contains(" $it ") }) return Answer.NO
        if (YES.any { t.contains(" $it ") }) return Answer.YES
        return Answer.UNCLEAR
    }

    /** "done"/"ho gaya" after the user entered a PIN or logged in themselves. */
    fun isDone(transcript: String?): Boolean {
        val t = (transcript ?: "").lowercase()
        return listOf("done", "ho gaya", "ho gya", "hogaya", "finished", "continue", "हो गया", "आगे", "aage").any { t.contains(it) }
    }
}
