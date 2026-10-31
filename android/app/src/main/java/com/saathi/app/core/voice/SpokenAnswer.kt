package com.saathi.app.core.voice

/**
 * How Saathi waits for a spoken answer, written for people who can't see the screen: silence never ends the
 * listening on its own; Saathi re-prompts and listens again, and only gives up (leaving the on-screen buttons)
 * after [MAX_SILENT_TURNS] silent turns, about a minute. Pure, so it is JVM-tested.
 */
object SpokenAnswer {
    /** Each silent turn lasts ~6 s (Vad), so six turns plus prompts is about a minute. */
    const val MAX_SILENT_TURNS = 6

    enum class Reprompt { SILENCE, UNCLEAR }

    /**
     * Listens for yes/no. True/false on an explicit answer; false after two unclear answers (SPEC §6.6);
     * null after [MAX_SILENT_TURNS] silent turns, when the caller leaves the decision to the buttons.
     * [listen] returns null for silence.
     */
    suspend fun yesNo(listen: suspend () -> String?, reprompt: suspend (Reprompt) -> Unit): Boolean? {
        var silent = 0
        var unclear = 0
        while (true) {
            val heard = listen()
            if (heard.isNullOrBlank()) {
                if (++silent >= MAX_SILENT_TURNS) return null
                reprompt(Reprompt.SILENCE)
                continue
            }
            when (YesNo.parse(heard)) {
                Answer.YES -> return true
                Answer.NO -> return false
                Answer.UNCLEAR -> {
                    if (++unclear >= 2) return false
                    reprompt(Reprompt.UNCLEAR)
                }
            }
        }
    }

    /** Listens for a free answer; null after [MAX_SILENT_TURNS] silent turns. */
    suspend fun freeText(listen: suspend () -> String?, reprompt: suspend () -> Unit): String? {
        repeat(MAX_SILENT_TURNS) { turn ->
            val heard = listen()
            if (!heard.isNullOrBlank()) return heard
            if (turn < MAX_SILENT_TURNS - 1) reprompt()
        }
        return null
    }
}
