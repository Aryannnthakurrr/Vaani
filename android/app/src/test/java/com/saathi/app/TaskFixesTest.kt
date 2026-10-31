package com.saathi.app

import com.saathi.app.core.agent.GuideTarget
import com.saathi.app.core.agent.ScreenNode
import com.saathi.app.core.agent.ScreenSnapshot
import com.saathi.app.core.voice.LocalIntentRules
import com.saathi.app.features.tasks.DayWords
import com.saathi.app.features.tasks.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskFixesTest {

    @Test
    fun pickupWordsAreDroppedFromCabDestinations() {
        assertEquals("India Gate", DeepLinks.cleanDestination("यहाँ से India Gate"))
        assertEquals("India Gate", DeepLinks.cleanDestination("यहां से India Gate"))
        assertEquals("Saket", DeepLinks.cleanDestination("from here to Saket"))
        assertEquals("Saket", DeepLinks.cleanDestination("yahan se Saket"))
        assertEquals("Connaught Place", DeepLinks.cleanDestination("Connaught Place"))
        assertNull(DeepLinks.cleanDestination("  "))
    }

    @Test
    fun onlyCleanPlaceNamesSkipTheCloudRouter() {
        assertTrue(LocalIntentRules.cleanPlace("Saket"))
        assertTrue(LocalIntentRules.cleanPlace("Connaught Place"))
        assertFalse(LocalIntentRules.cleanPlace("मुझे Saket"))
        assertFalse(LocalIntentRules.cleanPlace("Saket jana hai"))
        assertFalse(LocalIntentRules.cleanPlace(null))
    }

    @Test
    fun houseHelpDatesUseProntosWords() {
        val today = LocalDate.of(2026, 10, 31)
        assertEquals("today (Sat 31 Oct)", DayWords.of("2026-10-31", today))
        assertEquals("tomorrow (Sun 1 Nov)", DayWords.of("2026-11-01", today))
        assertEquals("Tue 3 Nov", DayWords.of("2026-11-03", today))
        assertEquals("tomorrow", DayWords.of("tomorrow", today))
    }

    @Test
    fun helpChahiyeMeansHouseHelp() {
        assertEquals("book_house_help", LocalIntentRules.route("mujhe help chahiye").intent)
        assertEquals("book_house_help", LocalIntentRules.route("kal ke liye helper chahiye").intent)
    }

    @Test
    fun sendingAPhotoIsGuided() {
        assertEquals("guide", LocalIntentRules.route("send this photo to Aarav").intent)
        assertEquals("guide", LocalIntentRules.route("ye photo Aarav ko bhej do").intent)
    }

    @Test
    fun visibleWhatsAppIsFoundOnTheShareSheet() {
        val snap = ScreenSnapshot("com.sec.android.gallery3d", null, false, listOf(
            ScreenNode("LinearLayout", clickable = true, bounds = listOf(40, 1800, 240, 2000)),
            ScreenNode("TextView", text = "WhatsApp", bounds = listOf(60, 1940, 220, 1990)),
            ScreenNode("TextView", text = "Quick Share", bounds = listOf(300, 1940, 460, 1990)),
        ))
        assertEquals("WhatsApp", GuideTarget.app("Send this photo to Aarav\nSend it on WhatsApp (in the share list, pick WhatsApp)."))
        assertEquals(listOf(40, 1800, 240, 2000), GuideTarget.visible("WhatsApp", snap)?.bounds)
        assertNull(GuideTarget.visible("Telegram", snap))
    }
}
