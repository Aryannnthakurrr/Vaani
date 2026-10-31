package com.saathi.app

import com.saathi.app.core.net.Appointment
import com.saathi.app.features.appointments.AppointmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class AppointmentStateTest {
    private val now = ZonedDateTime.parse("2026-10-31T18:00:00+05:30[Asia/Kolkata]")
    private val appt = Appointment(doctor = "Dr. Meera Iyer", clinic = "City Care Hospital", address = "Sector 18, Gurugram",
        phone = "+91 11 4000 0000", startIso = "2026-11-01T11:00:00+05:30")

    @Test
    fun cabToTheAppointmentGoesToTheClinic() {
        listOf("my doctor's appointment", "doctor ke paas", "the clinic", "अपॉइंटमेंट").forEach {
            assertEquals(it, "City Care Hospital, Sector 18, Gurugram", AppointmentState.destinationFor(it, appt, now))
        }
        assertNull(AppointmentState.destinationFor("Apollo Hospital", appt, now)) // a named place stays as said
        val past = appt.copy(startIso = "2026-10-30T11:00:00+05:30")
        assertNull(AppointmentState.destinationFor("my appointment", past, now)) // old appointment: don't guess
    }

    @Test
    fun contextLineForTheAssistant() {
        assertEquals("Next appointment: Dr. Meera Iyer at City Care Hospital, tomorrow at 11:00 AM (Sector 18, Gurugram).",
            AppointmentState.contextLine(appt, now))
        assertNull(AppointmentState.contextLine(null, now))
    }
}
