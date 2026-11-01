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
    fun insert(appt: Appointment): Long? = insertEvent(
        title = appt.doctor?.let { "Appointment: $it" } ?: "Doctor appointment",
        location = listOfNotNull(appt.clinic, appt.address).joinToString(", "),
        description = listOfNotNull(
            appt.phone?.let { "Clinic phone: $it" }, appt.fee?.let { "Fee: $it" }, "Booked with Vaani",
        ).joinToString("\n"),
        startIso = appt.startIso,
        endIso = null,
    )

    /** Any appointment event; [endIso] defaults to 30 minutes after the start. Alerts 1 day and 2 hours before. */
    fun insertEvent(title: String, location: String, description: String, startIso: String?, endIso: String?): Long? {
        if (!granted(Manifest.permission.WRITE_CALENDAR) || !granted(Manifest.permission.READ_CALENDAR)) return null
        val zone = java.time.ZoneId.systemDefault()
        val start = ReminderPlanner.parseStart(startIso, zone) ?: return null
        val calendarId = writableCalendar() ?: return null
        val startMs = start.toInstant().toEpochMilli()
        val endMs = ReminderPlanner.parseStart(endIso, zone)?.toInstant()?.toEpochMilli()?.takeIf { it > startMs }
            ?: (startMs + 30 * 60_000L)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DESCRIPTION, description)
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
