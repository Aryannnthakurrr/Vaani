package com.saathi.app

import com.saathi.app.core.net.Slots
import com.saathi.app.features.tasks.DeepLinks
import com.saathi.app.features.tasks.LatLng
import com.saathi.app.features.tasks.NearbyBox
import com.saathi.app.features.tasks.PlaceLookup
import com.saathi.app.features.tasks.Tasks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskLinksTest {
    private val apollo = LatLng(28.5402, 77.2833)

    @Test
    fun uberLinkUsesTheNearbyPlace() = runTest {
        val asked = mutableListOf<String>()
        val places = PlaceLookup { asked += it; apollo }
        val link = Tasks.UBER.deeplink!!.invoke(Slots(destination = "Apollo Hospital"), places)
        assertEquals(listOf("Apollo Hospital"), asked)
        assertEquals(DeepLinks.uberString("Apollo Hospital", apollo.lat, apollo.lng), link)
    }

    @Test
    fun uberOpensTheAppWhenThePlaceIsUnknown() = runTest {
        assertNull(Tasks.UBER.deeplink!!.invoke(Slots(destination = "Apollo Hospital"), PlaceLookup { null }))
        // No destination yet: nothing is looked up.
        assertNull(Tasks.UBER.deeplink!!.invoke(Slots(destination = " "), PlaceLookup { error("must not look up") }))
    }

    @Test
    fun prontoHasNoDeepLink() {
        // Pronto's only route, pronto://service/<id>, needs server-side listing ids, so the task opens the app.
        assertNull(Tasks.HOUSE_HELP.deeplink)
    }

    @Test
    fun nearbyBoxIsCentredOnTheUser() {
        val box = NearbyBox.around(LatLng(28.0, 77.0), radius = 0.3)
        assertArrayEquals(doubleArrayOf(27.7, 76.7, 28.3, 77.3), box, 1e-9)
    }
}
