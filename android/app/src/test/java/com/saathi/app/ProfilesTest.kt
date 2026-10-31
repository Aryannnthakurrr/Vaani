package com.saathi.app

import com.saathi.app.core.profile.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

class ProfilesTest {
    private val all = Profiles.parse(File(Fixtures.root, "profiles/profiles.json").readText())
    private fun p(id: String) = all.first { it.id == id }

    @Test
    fun theDemoUsersLoad() {
        assertEquals(listOf("ramesh", "sunita", "arjun", "kamla"), all.map { it.id })
        assertTrue(all.all { it.messages.isNotEmpty() && it.cases.isNotEmpty() && it.appointment != null })
    }

    @Test
    fun contextLineCarriesConditionsAndPreferences() {
        val line = Profiles.contextLine(p("sunita"))
        assertTrue(line, line.contains("Sunita Verma, 67") && line.contains("high blood pressure") && line.contains("low-salt"))
    }

    @Test
    fun appointmentsAreRelativeToNow() {
        val now = ZonedDateTime.parse("2026-11-01T08:00:00+05:30[Asia/Kolkata]")
        assertEquals("2026-11-02T10:30:00+05:30", Profiles.appointment(p("ramesh"), now)!!.startIso)
    }

    @Test
    fun rideAdviceFollowsTheBody() {
        assertEquals("car", Profiles.rideAdvice(p("ramesh"), "auto")?.vehicle) // knees: no auto
        assertNull(Profiles.rideAdvice(p("ramesh"), null)) // a car was already the plan
        assertEquals("xl", Profiles.rideAdvice(p("kamla"), null)?.vehicle) // wheelchair needs room
        assertNull(Profiles.rideAdvice(p("arjun"), "auto")) // nothing in his profile argues against an auto
        assertNull(Profiles.rideAdvice(null, "auto"))
    }
}
