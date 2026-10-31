package com.saathi.app

import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.CallLine
import com.saathi.app.core.net.ClinicCallRequest
import com.saathi.app.features.calls.LocalClinicCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalClinicCallTest {
    private val appt = Appointment(doctor = "Dr. Anil Mehta", clinic = "Mehta Heart Clinic", startIso = "2026-10-31T16:00:00")
    private fun req(vararg lines: Pair<String, String>, style: String = "en") =
        ClinicCallRequest("Kamla", appt, style, lines.map { CallLine(it.first, it.second) })

    @Test
    fun happyCallConfirmsAndReportsWait() {
        val first = LocalClinicCall.turn(req())
        assertTrue(first.say.contains("Saathi") && first.say.contains("Kamla") && first.say.contains("4:00 PM"))
        assertFalse(first.done)
        val second = LocalClinicCall.turn(req("agent" to first.say, "clinic" to "Yes, confirmed"))
        assertTrue(second.say.contains("waiting time"))
        val third = LocalClinicCall.turn(req("agent" to first.say, "clinic" to "Yes", "agent" to second.say, "clinic" to "about 20 minutes"))
        assertTrue(third.done)
        assertEquals(20, third.waitingMinutes)
        assertTrue(third.summary!!.contains("20 minutes"))
    }

    @Test
    fun refusalEndsCallAndHindiSummary() {
        val first = LocalClinicCall.turn(req(style = "hi"))
        val turn = LocalClinicCall.turn(req("agent" to first.say, "clinic" to "nahi, koi booking nahi hai", style = "hi"))
        assertTrue(turn.done)
        assertEquals(false, turn.confirmed)
        assertTrue(turn.summary!!.contains("पक्की नहीं"))
    }

    @Test
    fun waitingTimeParsing() {
        assertEquals(45, LocalClinicCall.waitingMinutes("roughly 45 mins"))
        assertEquals(60, LocalClinicCall.waitingMinutes("an hour"))
        assertEquals(120, LocalClinicCall.waitingMinutes("2 ghante"))
        assertNull(LocalClinicCall.waitingMinutes("not sure"))
    }
}
