package com.saathi.app.features.care

import com.saathi.app.core.data.Settings
import com.saathi.app.core.net.BackendClient
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown with the backend's plain-language reason, or "can't reach the server". */
class CareUnavailable(message: String) : Exception(message)

/** The backend's symptom-to-care routes (backend `routers/care.py`). Triage can take ~20 s when Gemini is slow. */
class CareClient(private val settings: () -> Settings) {
    private val http = OkHttpClient.Builder().addInterceptor(com.saathi.app.core.net.UsageMeter.interceptor).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    suspend fun start(body: CareStart): String =
        send("/care/session", SaathiJson.encodeToString(CareStart.serializer(), body), StartReply.serializer()).sessionId

    suspend fun intake(session: String, text: String): IntakeReply =
        send("/care/$session/intake", JsonObject(mapOf("text" to JsonPrimitive(text))).toString(), IntakeReply.serializer())

    suspend fun events(session: String, after: Int): List<CareEvent> =
        send("/care/$session/events?after=$after", null, EventsReply.serializer()).events

    suspend fun search(session: String) {
        send("/care/$session/search", "{}", JsonObject.serializer())
    }

    suspend fun book(session: String, body: BookRequest) {
        send("/care/$session/book", SaathiJson.encodeToString(BookRequest.serializer(), body), JsonObject.serializer())
    }

    /** Starts the siren countdown on the server side; returns its length in seconds. */
    suspend fun emergencyAlert(session: String): Int =
        (send("/care/$session/emergency/alert", "{}", JsonObject.serializer())["countdown_sec"] as? JsonPrimitive)
            ?.content?.toIntOrNull() ?: 15

    suspend fun emergencyDispatch(session: String, trigger: String, landmark: String?) {
        val body = buildMap {
            put("trigger", JsonPrimitive(trigger))
            if (landmark != null) put("landmark", JsonPrimitive(landmark))
        }
        send("/care/$session/emergency/dispatch", JsonObject(body).toString(), JsonObject.serializer())
    }

    suspend fun emergencyCancel(session: String) {
        send("/care/$session/emergency/cancel", "{}", JsonObject.serializer())
    }

    suspend fun setLocation(session: String, location: CareLocation) {
        send("/care/$session/location", SaathiJson.encodeToString(CareLocation.serializer(), location), JsonObject.serializer())
    }

    private suspend fun <T> send(path: String, body: String?, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val s = settings()
        val req = Request.Builder().url(s.backendUrl.trimEnd('/') + path).header(BackendClient.DEVICE_HEADER, s.deviceId)
            .apply { if (body != null) post(body.toRequestBody(json)) }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CareUnavailable(detail(text) ?: "Server error ${resp.code}")
                SaathiJson.decodeFromString(serializer, text)
            }
        } catch (e: IOException) {
            throw CareUnavailable("Can't reach the Saathi server.")
        }
    }

    /** FastAPI errors look like {"detail": "..."}. */
    private fun detail(text: String): String? =
        runCatching { (SaathiJson.parseToJsonElement(text) as JsonObject)["detail"] }.getOrNull()?.toString()?.trim('"')
}
