package com.saathi.app

import com.saathi.app.core.voice.NameMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameMatcherTest {
    private val contacts = listOf("Keshav Goel", "Aryan Thakur", "Pooja Sharma", "Ramesh Kumar")

    @Test
    fun nearMissesBecomeTheContact() {
        assertEquals("Keshav", NameMatcher.closest("Keshu", contacts))
        assertEquals("Keshav", NameMatcher.closest("keshaw", contacts))
        assertEquals("Ramesh", NameMatcher.closest("Ramesh", contacts))
    }

    @Test
    fun unrelatedWordsAreLeftAlone() {
        assertNull(NameMatcher.closest("photo", contacts))
        assertNull(NameMatcher.closest("Pronto", contacts)) // shares "P" with Pooja, nothing else
        assertNull(NameMatcher.closest("Kesar", listOf("Keshav")))
    }

    @Test
    fun sentencesOnlyFixWordsInNamePositions() {
        assertEquals("I want to send this photo to Keshav", NameMatcher.correctInSentence("I want to send this photo to Keshu", contacts))
        assertEquals("Keshav ko photo bhejo", NameMatcher.correctInSentence("Keshu ko photo bhejo", contacts))
        // "this photo" is not after a cue, so "photo" is never touched even if a contact were close.
        assertEquals("send this photo", NameMatcher.correctInSentence("send this photo", listOf("Photon Das")))
    }

    @Test
    fun patientNameAnswers() {
        assertEquals("Keshav Goel", NameMatcher.correctName("keshav goel.", contacts))
        assertEquals("Mera naam Keshav hai", NameMatcher.correctName("Mera naam Keshu hai", contacts))
        assertEquals("Sunita", NameMatcher.correctName("Sunita", contacts)) // unknown names stay as heard
    }
}
