package com.saathi.app

import com.saathi.app.core.voice.LanguageSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageSwitchTest {
    @Test
    fun hindiRequests() {
        listOf(
            "mujhe English nahi aati", "Mujhe bas Hindi aati hai", "sirf Hindi mein bolo", "Hindi mein baat karo",
            "I don't understand English", "please speak in Hindi", "मुझे अंग्रेज़ी नहीं आती", "हिंदी में बोलो",
        ).forEach { assertEquals(it, "hi", LanguageSwitch.detect(it)) }
    }

    @Test
    fun englishRequests() {
        listOf("Hindi nahi aati, English mein bolo", "please speak English", "only English", "I can't understand Hindi")
            .forEach { assertEquals(it, "en", LanguageSwitch.detect(it)) }
    }

    @Test
    fun ordinarySentencesDontSwitch() {
        listOf("book a cab to Apollo", "Hindi film ka gaana chalao", "mujhe English medicine ka naam batao", "", null)
            .forEach { assertNull(it, LanguageSwitch.detect(it)) }
    }
}
