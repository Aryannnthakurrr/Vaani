package com.saathi.app

import com.saathi.app.core.net.Appointment
import com.saathi.app.features.inbox.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlannerTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun startTimeWithAndWithoutOffset() {
        val local = ReminderPlanner.parseStart("2026-10-31T16:00:00", ist)!!
        assertEquals(16, local.hour)
        val offset = ReminderPlanner.parseStart("2026-10-31T10:30:00Z", ist)!!
        assertEquals(16, offset.hour)
        assertNull(ReminderPlanner.parseStart("today 4 pm", ist))
        assertNull(ReminderPlanner.parseStart(null, ist))
    }

    /** The backend returns zone-less times; both appointment reminders must still be planned. */
    @Test
    fun appointmentRemindersFromZonelessBackendTime() {
        val now = ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, ist)
        val appt = Appointment(doctor = "Dr. Anil Mehta", clinic = "Mehta Heart Clinic", startIso = "2026-10-31T16:00:00")
        val reminders = ReminderPlanner.forAppointment(appt, now)
        assertEquals(2, reminders.size)
        val twoHoursBefore = ZonedDateTime.of(2026, 10, 31, 14, 0, 0, 0, ist).toInstant().toEpochMilli()
        assertEquals(twoHoursBefore, reminders.last().triggerAt)
    }
}
