package com.saathi.app.core

import android.content.Context
import com.saathi.app.core.agent.MockAgentScripts
import com.saathi.app.core.data.SaathiDb
import com.saathi.app.core.data.SettingsStore
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.BackendClient
import com.saathi.app.core.net.FakeBackendClient
import com.saathi.app.core.overlay.AlertRouter
import com.saathi.app.core.voice.Phrases
import com.saathi.app.core.voice.Speaker
import com.saathi.app.features.inbox.CalendarWriter
import com.saathi.app.features.inbox.DigestRepository
import com.saathi.app.features.inbox.MessageIngestor
import com.saathi.app.features.inbox.ReminderScheduler
import com.saathi.app.features.scam.CallGuard
import com.saathi.app.features.scam.ContactsLookup
import com.saathi.app.features.welfare.WelfareManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI: one instance per process, created by [com.saathi.app.SaathiApp]. */
class AppContainer(val context: Context) {
    /** A failure in one background job is logged, never allowed to crash the whole app. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e("Saathi", "background job failed", e)
    })
    val settings = SettingsStore(context, scope)
    val db: SaathiDb by lazy { SaathiDb.create(context) }
    val phrases = Phrases(context)
    val notifier = Notifier(context)
    val alerts = AlertRouter(notifier)

    private val realBackend = BackendClient({ settings.current.backendUrl }, { settings.current.deviceId })
    private val fakeBackend = FakeBackendClient({ mockScripts }, { settings.current.userName.ifBlank { null } })

    /** Real HTTP backend, or the on-device fake when chosen in Settings. Re-read on every call. */
    val backend: BackendApi get() = if (settings.current.useFakeBackend) fakeBackend else realBackend

    val speaker = Speaker(context, { backend }, phrases)

    /** Debug-only fixture scripts (bundled from /fixtures in debug builds; empty in release). */
    val mockScripts: MockAgentScripts by lazy {
        val names = runCatching { context.assets.list("agent_scripts")?.toList() }.getOrNull().orEmpty()
        MockAgentScripts.parse(names.filter { it.endsWith(".json") }.map { name ->
            context.assets.open("agent_scripts/$name").bufferedReader().use { it.readText() }
        })
    }

    val contacts by lazy { ContactsLookup(context) }
    val callGuard by lazy { CallGuard(this) }
    val ingestor by lazy { MessageIngestor(this) }
    val digest by lazy { DigestRepository(this) }
    val reminders by lazy { ReminderScheduler(context) }
    val calendar by lazy { CalendarWriter(context) }
    val welfare by lazy { WelfareManager(this) }
    val assistant by lazy { Assistant(this) }
}
