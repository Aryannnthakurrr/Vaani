package com.saathi.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saathi.app.BuildConfig
import com.saathi.app.core.net.FamilyContact
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("saathi_settings")

data class Settings(
    /** auto | en | hi | hinglish. "auto" mirrors whatever the user last spoke. */
    val language: String = "auto",
    val userName: String = "",
    val contacts: List<FamilyContact> = emptyList(),
    val welfareCheckTime: String = "12:00",
    val welfarePromptTime: String = "11:30",
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val useFakeBackend: Boolean = false,
    /** Debug/demo: run tasks and guide mode against recorded fixture screens instead of real apps. */
    val replayMode: Boolean = false,
    val notifyFamilyOnScam: Boolean = true,
    val deviceId: String = "",
    /** Install secret for the backend (core/net/DeviceAuth.kt). */
    val deviceKey: String = "",
    val lastUnlockTs: Long = 0,
    val lastHeartbeatTs: Long = 0,
    val lastStyle: String = "en",
    /** Last booked appointment (JSON of [com.saathi.app.core.net.Appointment]) for the clinic-call feature. */
    val lastAppointment: String? = null,
    /** Demo profile in use (fixtures/profiles), or "" for the real user. */
    val profileId: String = "",
    /** The setup list was finished once; skipped permissions are asked for later, never by reopening setup. */
    val setupDone: Boolean = false,
) {
    /** Style to speak in when we have no fresh utterance to mirror. */
    val replyStyle: ReplyStyle get() = if (language == "auto") autoStyle(ReplyStyle.from(lastStyle)) else ReplyStyle.from(language)

    /** "auto": English when the user speaks English, Hinglish for anything else (Hindi included). */
    fun autoStyle(heard: ReplyStyle): ReplyStyle = if (language == "auto" && heard == ReplyStyle.HI) ReplyStyle.HINGLISH else heard
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val language = stringPreferencesKey("language")
        val userName = stringPreferencesKey("user_name")
        val contacts = stringPreferencesKey("contacts")
        val welfareCheck = stringPreferencesKey("welfare_check")
        val welfarePrompt = stringPreferencesKey("welfare_prompt")
        val backendUrl = stringPreferencesKey("backend_url")
        val fake = booleanPreferencesKey("fake_backend")
        val replay = booleanPreferencesKey("replay_mode")
        val notifyFamily = booleanPreferencesKey("notify_family_scam")
        val deviceId = stringPreferencesKey("device_id")
        val deviceKey = stringPreferencesKey("device_key")
        val lastUnlock = longPreferencesKey("last_unlock")
        val lastHeartbeat = longPreferencesKey("last_heartbeat")
        val lastStyle = stringPreferencesKey("last_style")
        val lastAppointment = stringPreferencesKey("last_appointment")
        val profileId = stringPreferencesKey("profile_id")
        val setupDone = booleanPreferencesKey("setup_done")
    }

    private val contactsSer = ListSerializer(FamilyContact.serializer())

    private fun read(p: Preferences) = Settings(
        language = p[K.language] ?: "auto",
        userName = p[K.userName] ?: "",
        contacts = p[K.contacts]?.let { runCatching { SaathiJson.decodeFromString(contactsSer, it) }.getOrNull() }
            ?: emptyList(),
        welfareCheckTime = p[K.welfareCheck] ?: "12:00",
        welfarePromptTime = p[K.welfarePrompt] ?: "11:30",
        backendUrl = p[K.backendUrl] ?: BuildConfig.DEFAULT_BACKEND_URL,
        useFakeBackend = p[K.fake] ?: false,
        replayMode = p[K.replay] ?: false,
        notifyFamilyOnScam = p[K.notifyFamily] ?: true,
        deviceId = p[K.deviceId] ?: "",
        deviceKey = p[K.deviceKey] ?: "",
        lastUnlockTs = p[K.lastUnlock] ?: 0,
        lastHeartbeatTs = p[K.lastHeartbeat] ?: 0,
        lastStyle = p[K.lastStyle] ?: "en",
        lastAppointment = p[K.lastAppointment],
        profileId = p[K.profileId] ?: "",
        setupDone = p[K.setupDone] ?: false,
    )

    val flow: StateFlow<Settings> = context.dataStore.data.map(::read).stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Settings straight from DataStore, including an edit made a moment ago ([current] can lag behind one). */
    suspend fun fresh(): Settings = read(context.dataStore.data.first())

    /** Current settings; blocks only on the very first read after process start. */
    val current: Settings
        get() = flow.value.takeIf { it.deviceId.isNotEmpty() } ?: runBlocking { flow.first { it.deviceId.isNotEmpty() } }

    /** Creates the device UUID and its install secret on first launch (existing installs get a secret on update). */
    suspend fun ensureDeviceId() {
        context.dataStore.edit {
            if (it[K.deviceKey].isNullOrEmpty()) it[K.deviceKey] = com.saathi.app.core.net.DeviceAuth.newKey()
            if (it[K.deviceId].isNullOrEmpty()) it[K.deviceId] = UUID.randomUUID().toString()
        }
    }

    suspend fun setLanguage(v: String) = context.dataStore.edit { it[K.language] = v }
    suspend fun setSetupDone() = context.dataStore.edit { it[K.setupDone] = true }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[K.userName] = v }
    suspend fun setContacts(v: List<FamilyContact>) =
        context.dataStore.edit { it[K.contacts] = SaathiJson.encodeToString(contactsSer, v) }
    suspend fun setWelfareTimes(check: String, prompt: String) = context.dataStore.edit {
        it[K.welfareCheck] = check
        it[K.welfarePrompt] = prompt
    }
    suspend fun setBackendUrl(v: String) = context.dataStore.edit { it[K.backendUrl] = v }
    suspend fun setUseFakeBackend(v: Boolean) = context.dataStore.edit { it[K.fake] = v }
    suspend fun setReplayMode(v: Boolean) = context.dataStore.edit { it[K.replay] = v }
    suspend fun setNotifyFamilyOnScam(v: Boolean) = context.dataStore.edit { it[K.notifyFamily] = v }
    suspend fun setLastUnlock(ts: Long) = context.dataStore.edit { it[K.lastUnlock] = ts }
    suspend fun setLastHeartbeat(ts: Long) = context.dataStore.edit { it[K.lastHeartbeat] = ts }
    suspend fun setLastAppointment(json: String) = context.dataStore.edit { it[K.lastAppointment] = json }
    suspend fun setProfileId(v: String) = context.dataStore.edit { it[K.profileId] = v }
    suspend fun clearLastAppointment() = context.dataStore.edit { it.remove(K.lastAppointment) }
    suspend fun setLastStyle(style: ReplyStyle) = context.dataStore.edit { it[K.lastStyle] = style.wire }
}
