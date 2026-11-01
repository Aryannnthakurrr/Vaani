package com.saathi.app.features.appointments

import com.saathi.app.core.net.Appointment
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What every workflow knows about the user's next appointment: the cab task resolves "to my doctor's appointment"
 * to the clinic, and the live conversation is told when and where it is. Pure, so it is JVM-tested.
 */
object AppointmentState {
    private val REFERS_TO_APPOINTMENT = Regex(
        // Speech-to-text spells these many ways, in both scripts ("appoinment", डाक्टर, अपॉइन्टमेंट, अप्वाइंटमेंट).
        "(?i)\\b(my |the )?(doctor'?s?|docter|doctor ke|doctor wal[ae]|clinic|app?oi?n?t?ment|checkup|check-up)\\b" +
            "|ड[ॉाो]?क्टर|प(ॉ|ो|्वा)इ(ं|न्)टमें?ट|क्लि?ी?निक",
    )

    /** Start time; a time without an offset ("2026-11-01T11:00:00") is read in the phone's time zone. */
    fun start(appt: Appointment?): OffsetDateTime? = appt?.startIso?.let { iso ->
        runCatching { OffsetDateTime.parse(iso) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toOffsetDateTime() }.getOrNull()
    }

    /** The appointment if it hasn't happened yet (or started under two hours ago). */
    fun upcoming(appt: Appointment?, now: ZonedDateTime): Appointment? {
        val at = start(appt) ?: return null
        return appt.takeIf { at.toInstant().isAfter(now.toInstant().minus(2, ChronoUnit.HOURS)) }
    }

    /**
     * "book a cab to my doctor's appointment" → the clinic's name and address. Returns null when the destination
     * doesn't refer to the appointment, or there is no upcoming one with a place.
     */
    fun refersToAppointment(destination: String?): Boolean =
        !destination.isNullOrBlank() && REFERS_TO_APPOINTMENT.containsMatchIn(destination)

    fun destinationFor(destination: String?, appt: Appointment?, now: ZonedDateTime): String? {
        if (destination.isNullOrBlank() || !REFERS_TO_APPOINTMENT.containsMatchIn(destination)) return null
        val next = upcoming(appt, now) ?: return null
        return listOfNotNull(next.clinic, next.address).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }

    /** One line of context for the assistant's conversation; never includes phone numbers or fees. */
    fun contextLine(appt: Appointment?, now: ZonedDateTime): String? {
        val next = upcoming(appt, now) ?: return null
        val at = start(next) ?: return null
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.atZoneSameInstant(now.zone).toLocalDate())
        val day = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> at.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
        }
        val who = listOfNotNull(next.doctor, next.clinic).joinToString(" at ").ifBlank { "a doctor" }
        val where = next.address?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "Next appointment: $who, $day at ${at.format(DateTimeFormatter.ofPattern("h:mm a"))}$where."
    }
}
