package com.saathi.app.features.profile

import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.profile.Profile
import com.saathi.app.core.profile.Profiles
import com.saathi.app.util.SaathiJson
import java.time.ZonedDateTime

/**
 * Switches between the demo users (Settings → Profile). A switch sets the name, language and next appointment, and
 * replays the profile's messages through the normal inbox path, so redaction, the private vault and Scam Shield all
 * run on them. Only messages from [DEMO_SOURCE] are cleared on a switch; real messages are never touched.
 */
class ProfileManager(private val c: AppContainer) {
    /** Demo profiles ship with debug builds (fixtures are debug assets); release builds have none. */
    val all: List<Profile> by lazy {
        runCatching { c.context.assets.open("profiles/profiles.json").bufferedReader().use { Profiles.parse(it.readText()) } }
            .getOrDefault(emptyList())
    }

    fun current(): Profile? = c.settings.current.profileId.takeIf { it.isNotEmpty() }?.let { id -> all.firstOrNull { it.id == id } }

    suspend fun switchTo(p: Profile) {
        c.settings.setProfileId(p.id)
        c.settings.setUserName(p.name.substringBefore(" "))
        c.settings.setLanguage(p.language)
        val now = ZonedDateTime.now()
        Profiles.appointment(p, now)?.let {
            c.settings.setLastAppointment(SaathiJson.encodeToString(Appointment.serializer(), it))
        }
        c.db.messages().deleteFrom(DEMO_SOURCE)
        val nowMs = System.currentTimeMillis()
        for (m in p.messages.sortedByDescending { it.minutesAgo }) {
            c.ingestor.ingest(DEMO_SOURCE, m.sender, m.text, nowMs - m.minutesAgo * 60_000)
        }
    }

    /** Back to the real user: demo messages and the demo appointment go, the language follows speech again. */
    suspend fun clear() {
        c.settings.setProfileId("")
        c.settings.setLanguage("auto")
        c.settings.clearLastAppointment()
        c.db.messages().deleteFrom(DEMO_SOURCE)
    }

    /** What the assistant should know about this user, for prompts; null for the real user. */
    fun contextLine(): String? = current()?.let(Profiles::contextLine)

    companion object {
        const val DEMO_SOURCE = "saathi.demo.profile"
    }
}
