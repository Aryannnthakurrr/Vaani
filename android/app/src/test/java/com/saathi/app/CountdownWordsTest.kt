package com.saathi.app

import com.saathi.app.features.care.CountdownWords
import com.saathi.app.features.care.CountdownWords.Said
import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownWordsTest {
    @Test
    fun spokenAnswersDuringTheCountdown() {
        mapOf(
            "I'm OK" to Said.OK, "main theek hoon" to Said.OK, "मैं ठीक हूँ" to Said.OK, "ambulance mat bhejo" to Said.OK,
            "jaldi bhejo" to Said.SEND, "please send it" to Said.SEND, "बचाओ" to Said.SEND,
            "I'm not OK" to Said.NOTHING, "theek nahi hoon, help" to Said.NOTHING, "don't cancel" to Said.NOTHING,
            "" to Said.NOTHING, null to Said.NOTHING,
        ).forEach { (heard, want) -> assertEquals(heard.toString(), want, CountdownWords.parse(heard)) }
    }
}
