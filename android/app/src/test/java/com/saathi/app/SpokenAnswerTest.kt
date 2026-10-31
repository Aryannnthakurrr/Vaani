package com.saathi.app

import com.saathi.app.core.voice.SpokenAnswer
import com.saathi.app.core.voice.SpokenAnswer.Reprompt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenAnswerTest {
    /** Plays [heard] in order (null = a silent turn) and records each re-prompt. */
    private class Script(vararg heard: String?) {
        private val turns = ArrayDeque(heard.toList())
        val reprompts = mutableListOf<Reprompt>()
        var listens = 0
        suspend fun listen(): String? { listens++; return turns.removeFirstOrNull() }
        suspend fun reprompt(r: Reprompt) { reprompts += r }
    }

    @Test
    fun aLateYesStillCounts() = runTest {
        // A blind user who answers after two silent turns must not be left with only the buttons.
        val s = Script(null, null, "haan")
        assertEquals(true, SpokenAnswer.yesNo(s::listen, s::reprompt))
        assertEquals(listOf(Reprompt.SILENCE, Reprompt.SILENCE), s.reprompts)
    }

    @Test
    fun noAndUnclearRules() = runTest {
        assertEquals(false, SpokenAnswer.yesNo(Script("nahi")::listen) {})
        // Unclear twice is a no (SPEC §6.6); silence in between doesn't reset that.
        val s = Script("what is the price", null, "Apollo")
        assertEquals(false, SpokenAnswer.yesNo(s::listen, s::reprompt))
        assertEquals(listOf(Reprompt.UNCLEAR, Reprompt.SILENCE), s.reprompts)
    }

    @Test
    fun longSilenceLeavesItToTheButtons() = runTest {
        val s = Script() // silent forever
        assertNull(SpokenAnswer.yesNo(s::listen, s::reprompt))
        assertEquals(SpokenAnswer.MAX_SILENT_TURNS, s.listens)
        assertEquals(SpokenAnswer.MAX_SILENT_TURNS - 1, s.reprompts.size)
    }

    @Test
    fun freeTextWaitsThroughSilence() = runTest {
        var reprompts = 0
        val s = Script(null, " ", "Apollo Hospital")
        assertEquals("Apollo Hospital", SpokenAnswer.freeText(s::listen) { reprompts++ })
        assertEquals(2, reprompts)
        val quiet = Script()
        assertNull(SpokenAnswer.freeText(quiet::listen) {})
        assertEquals(SpokenAnswer.MAX_SILENT_TURNS, quiet.listens)
    }
}
