package com.saathi.app.features.inbox

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.saathi.app.core.net.Appointment
import java.util.TimeZone

/** Inserts a booked appointment into the user's calendar with alerts 1 day and 2 hours before. */
class CalendarWriter(private val context: Context) {

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Returns the event id, or null without permission, without a writable calendar, or without a start time. */
    fun insert(appt: Appointment): Long? {
        if (!granted(Manifest.permission.WRITE_CALENDAR) || !granted(Manifest.permission.READ_CALENDAR)) return null
        val start = ReminderPlanner.parseStart(appt.startIso, java.time.ZoneId.systemDefault()) ?: return null
        val calendarId = writableCalendar() ?: return null
        val startMs = start.toInstant().toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, startMs + 30 * 60_000L)
            put(CalendarContract.Events.TITLE, appt.doctor?.let { "Appointment: $it" } ?: "Doctor appointment")
            put(CalendarContract.Events.EVENT_LOCATION, listOfNotNull(appt.clinic, appt.address).joinToString(", "))
            put(CalendarContract.Events.DESCRIPTION, listOfNotNull(
                appt.phone?.let { "Clinic phone: $it" }, appt.fee?.let { "Fee: $it" }, "Booked with Saathi",
            ).joinToString("\n"))
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
            val eventId = uri.lastPathSegment?.toLongOrNull() ?: return null
            for (minutes in listOf(1440, 120)) {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            eventId
        }.getOrNull()
    }

    /** Primary visible calendar the user can write to, else the first writable one. */
    private fun writableCalendar(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return@use id
                if (first == null) first = id
            }
            first
        }
    }.getOrNull()
}
