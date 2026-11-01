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

    /**
     * Whose memory (Meko datapack) the server uses: the chosen demo user, else the demo user with the same first name
     * as the phone's user ("Ramesh" after "Back to my own profile"). Each demo user has one datapack, and the free tier
     * has no room for a new one per phone. Null for a real user with no matching demo name.
     */
    fun memoryId(): String? {
        val s = c.settings.current
        if (s.profileId.isNotEmpty()) return s.profileId
        val name = s.userName.trim().substringBefore(" ")
        if (name.isEmpty()) return null
        return all.firstOrNull { it.name.substringBefore(" ").equals(name, ignoreCase = true) }?.id
    }

    suspend fun switchTo(p: Profile) {
        c.settings.setProfileId(p.id)
        c.settings.setUserName(p.name.substringBefore(" "))
        c.settings.setLanguage(p.language)
        val now = ZonedDateTime.now()
        // A booking this demo user really made (kept in their memory) wins over the profile's built-in example.
        val remembered = kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { c.backend.storedAppointment() }.getOrNull() }
        (remembered ?: Profiles.appointment(p, now))?.let {
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
