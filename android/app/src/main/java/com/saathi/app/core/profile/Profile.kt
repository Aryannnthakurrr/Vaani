package com.saathi.app.core.profile

import com.saathi.app.core.net.Appointment
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class ProfileAppointment(
    val doctor: String,
    val clinic: String,
    val address: String,
    @SerialName("in_days") val inDays: Long,
    val at: String,
    val reason: String = "",
)

@Serializable
data class ProfileMessage(val sender: String, val text: String, @SerialName("minutes_ago") val minutesAgo: Long)

@Serializable
data class ProfileCase(val say: String? = null, val show: String? = null, val expect: String)

/** A demo user (fixtures/profiles/profiles.json): conditions and preferences shape Saathi's decisions. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val sex: String,
    val language: String,
    val conditions: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val family: String? = null,
    val appointment: ProfileAppointment? = null,
    val messages: List<ProfileMessage> = emptyList(),
    val cases: List<ProfileCase> = emptyList(),
)

@Serializable
private data class ProfileFile(val profiles: List<Profile>)

/** Pure helpers for profiles, JVM-tested. */
object Profiles {
    fun parse(json: String): List<Profile> = SaathiJson.decodeFromString(ProfileFile.serializer(), json).profiles

    /** One line the assistant always knows (live voice, agent, camera). Health facts the user chose to share. */
    fun contextLine(p: Profile): String = buildString {
        append("User: ${p.name}, ${p.age}.")
        if (p.conditions.isNotEmpty()) append(" Health: ${p.conditions.joinToString(", ")}.")
        if (p.preferences.isNotEmpty()) append(" Preferences: ${p.preferences.joinToString("; ")}.")
        p.family?.let { append(" Family: $it.") }
    }

    fun appointment(p: Profile, now: ZonedDateTime): Appointment? = p.appointment?.let { a ->
        val (h, m) = a.at.split(":").map { it.toInt() }
        val start = now.toLocalDate().plusDays(a.inDays).atTime(h, m).atZone(now.zone)
        Appointment(doctor = a.doctor, clinic = a.clinic, address = a.address,
            startIso = start.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }

    private val MOBILITY = Regex("(?i)arthritis|knee|joint|hip|wheelchair|back pain|frail")
    private val WHEELCHAIR = Regex("(?i)wheelchair")

    /**
     * Why a different ride suits this person better, or null when what they asked for is fine. A wheelchair needs
     * a big car (XL); sore knees and joints make autos and bikes hard to get into.
     */
    fun rideAdvice(p: Profile?, vehicle: String?): RideAdvice? {
        p ?: return null
        val wants = vehicle?.lowercase() ?: "car"
        if (p.conditions.any { WHEELCHAIR.containsMatchIn(it) }) {
            return RideAdvice("xl", "a big car (Uber XL) has room for the wheelchair").takeIf { wants != "xl" }
        }
        if ((wants == "auto" || wants == "bike") && p.conditions.any { MOBILITY.containsMatchIn(it) }) {
            return RideAdvice("car", "a car is easier on the ${p.conditions.first { MOBILITY.containsMatchIn(it) }}")
        }
        return null
    }
}

/** A suggested ride type and the plain reason, said to the user before booking. */
data class RideAdvice(val vehicle: String, val reason: String)
