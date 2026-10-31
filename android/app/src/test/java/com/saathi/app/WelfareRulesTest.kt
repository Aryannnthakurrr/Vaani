package com.saathi.app

import com.saathi.app.features.inbox.ReminderPlanner
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.ClassifyResult
import com.saathi.app.features.welfare.WelfareRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WelfareRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 31, 11, 30, 0, 0, zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test fun checkInNeededOnlyWhenNoUnlockSinceEightPmYesterday() {
        assertTrue(WelfareRules.needsCheckIn(ms(now.minusDays(1).withHour(19).withMinute(59)), now))
        assertFalse(WelfareRules.needsCheckIn(ms(now.minusDays(1).withHour(20).withMinute(14)), now))
        assertFalse(WelfareRules.needsCheckIn(ms(now.withHour(7)), now))
        assertTrue(WelfareRules.needsCheckIn(0L, now))
    }

    @Test fun heartbeatRateLimit() {
        assertFalse(WelfareRules.shouldSendHeartbeat(1_000_000L, 1_000_000L + 9 * 60_000L))
        assertTrue(WelfareRules.shouldSendHeartbeat(1_000_000L, 1_000_000L + 10 * 60_000L))
    }

    @Test fun nextPromptTime() {
        assertEquals(now.plusDays(1), WelfareRules.nextAt("11:30", now))
        assertEquals(now.withHour(12).withMinute(0), WelfareRules.nextAt("12:00", now))
        assertEquals(now.plusDays(1), WelfareRules.nextAt("garbage", now))
    }

    @Test fun billReminderTwoDaysBeforeAtTen() {
        val r = ReminderPlanner.forMessage(ClassifyResult("1", "bill_due", 1240.0, "2026-11-05", "BSES", "Bill"), 1, now)
        assertEquals(ms(ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, zone)), r.single().triggerAt)
    }

    @Test fun lateBillRemindsSoonAndPastBillNotAtAll() {
        val soon = ReminderPlanner.forMessage(ClassifyResult("1", "bill_due", dueDate = "2026-11-01"), 1, now)
        assertEquals(ms(now.plusMinutes(1)), soon.single().triggerAt)
        assertTrue(ReminderPlanner.forMessage(ClassifyResult("1", "bill_due", dueDate = "2026-10-20"), 1, now).isEmpty())
    }

    @Test fun appointmentRemindersOneDayAndTwoHoursBefore() {
        val appt = Appointment(doctor = "Dr. Anil Mehta", startIso = "2026-11-02T16:00+05:30")
        val r = ReminderPlanner.forAppointment(appt, now)
        assertEquals(2, r.size)
        assertEquals(ms(ZonedDateTime.of(2026, 11, 1, 16, 0, 0, 0, zone)), r[0].triggerAt)
        assertEquals(ms(ZonedDateTime.of(2026, 11, 2, 14, 0, 0, 0, zone)), r[1].triggerAt)
    }
}
