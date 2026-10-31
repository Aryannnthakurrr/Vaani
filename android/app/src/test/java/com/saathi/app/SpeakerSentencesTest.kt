package com.saathi.app

import com.saathi.app.core.voice.Speaker
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerSentencesTest {
    @Test
    fun shortRepliesStayOneClip() {
        assertEquals(listOf("Okay. Booking it now."), Speaker.sentences("Okay. Booking it now."))
    }

    @Test
    fun longRepliesSplitAtSentenceEnds() {
        val text = "I am calling City Hospital and Apollo now. This takes a few minutes. मैं आपको बताती हूँ कि उन्होंने क्या कहा। Ok."
        assertEquals(
            listOf("I am calling City Hospital and Apollo now.", "This takes a few minutes. मैं आपको बताती हूँ कि उन्होंने क्या कहा। Ok."),
            Speaker.sentences(text),
        )
    }
}
