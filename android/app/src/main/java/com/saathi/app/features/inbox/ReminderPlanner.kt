package com.saathi.app.features.inbox

import com.saathi.app.core.data.ReminderEntity
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.ClassifyResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Builds reminders (SPEC §7.4): bills and renewals 2 days before the due date at 10:00,
 * appointments 1 day and 2 hours before. Reminders already in the past are dropped.
 */
object ReminderPlanner {
    private val morning = LocalTime.of(10, 0)

    fun forMessage(r: ClassifyResult, sourceId: Long?, now: ZonedDateTime): List<ReminderEntity> {
        val due = r.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return emptyList()
        val title = r.summary ?: r.category
        val list = when (r.category) {
            "bill_due" -> listOf(ReminderEntity(kind = "bill", title = "Bill due", body = title, sourceId = sourceId,
                triggerAt = billTrigger(due, now)))
            "subscription_renewal" -> listOf(ReminderEntity(kind = "renewal", title = "Renewal coming", body = title,
                sourceId = sourceId, triggerAt = billTrigger(due, now)))
            "appointment" -> listOf(ReminderEntity(kind = "appointment", title = "Appointment tomorrow", body = title,
                sourceId = sourceId, triggerAt = due.minusDays(1).atTime(morning).atZone(now.zone).toInstant().toEpochMilli()))
            else -> emptyList()
        }
        return list.filter { it.triggerAt > now.toInstant().toEpochMilli() }
    }

    /** Two days before at 10:00; if that has passed but the due date hasn't, remind in a minute. */
    private fun billTrigger(due: LocalDate, now: ZonedDateTime): Long {
        val planned = due.minusDays(2).atTime(morning).atZone(now.zone)
        val dueEnd = due.atTime(23, 59).atZone(now.zone)
        return when {
            planned.isAfter(now) -> planned.toInstant().toEpochMilli()
            dueEnd.isAfter(now) -> now.plusMinutes(1).toInstant().toEpochMilli()
            else -> 0L
        }
    }

    fun forAppointment(appt: Appointment, now: ZonedDateTime, zone: ZoneId = now.zone): List<ReminderEntity> {
        val start = parseStart(appt.startIso, zone)
            ?: return emptyList()
        val what = listOfNotNull(appt.doctor, appt.clinic).joinToString(", ").ifEmpty { "Doctor appointment" }
        val at = start.toLocalTime().toString()
        return listOf(
            ReminderEntity(kind = "appointment", title = "Appointment tomorrow", body = "$what at $at tomorrow.",
                triggerAt = start.minusDays(1).toInstant().toEpochMilli()),
            ReminderEntity(kind = "appointment", title = "Appointment in 2 hours", body = "$what at $at today.",
                triggerAt = start.minusHours(2).toInstant().toEpochMilli()),
        ).filter { it.triggerAt > now.toInstant().toEpochMilli() }
    }

    /** Appointment start as a zoned time; accepts "2026-10-31T16:00:00+05:30" and zone-less "2026-10-31T16:00:00". */
    fun parseStart(iso: String?, zone: ZoneId): ZonedDateTime? {
        iso ?: return null
        return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(iso).atZone(zone) }.getOrNull()
    }
}
