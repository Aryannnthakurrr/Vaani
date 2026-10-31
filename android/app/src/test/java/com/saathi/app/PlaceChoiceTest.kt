package com.saathi.app

import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.features.tasks.LatLng
import com.saathi.app.features.tasks.PlaceCandidate
import com.saathi.app.features.tasks.PlaceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceChoiceTest {
    private val delhi = PlaceCandidate(LatLng(28.54, 77.28), "New Delhi")
    private val faridabad = PlaceCandidate(LatLng(28.41, 77.31), "Faridabad")
    private val delhi2 = PlaceCandidate(LatLng(28.55, 77.27), "New Delhi")
    private val gurugram = PlaceCandidate(LatLng(28.46, 77.03), "Gurugram")

    @Test
    fun asksOnlyWhenAreasDiffer() {
        assertEquals(listOf(delhi, faridabad), PlaceChoice.options(listOf(delhi, delhi2, faridabad)))
        assertTrue(PlaceChoice.options(listOf(delhi, delhi2)).isEmpty()) // same city: just go
        assertTrue(PlaceChoice.options(listOf(delhi)).isEmpty())
        assertTrue(PlaceChoice.options(listOf(PlaceCandidate(delhi.at, null), faridabad)).isEmpty())
    }

    @Test
    fun questionNamesTheAreas() {
        val q = PlaceChoice.question("Apollo Hospital", listOf(delhi, faridabad, gurugram), ReplyStyle.EN)
        assertEquals("I found Apollo Hospital in New Delhi, Faridabad or Gurugram. Which one?", q)
    }

    @Test
    fun matchesByAreaOrOrdinal() {
        val opts = listOf(delhi, faridabad, gurugram)
        assertEquals(faridabad, PlaceChoice.match("Faridabad wala", opts))
        assertEquals(delhi, PlaceChoice.match("delhi", opts))
        assertEquals(faridabad, PlaceChoice.match("doosra", opts))
        assertEquals(delhi, PlaceChoice.match("the first one", opts))
        assertNull(PlaceChoice.match("the big one", opts)) // unclear: ask again, never guess
        assertNull(PlaceChoice.match("", opts))
    }
}
