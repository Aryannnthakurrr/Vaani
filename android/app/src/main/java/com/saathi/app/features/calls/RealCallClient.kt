package com.saathi.app.features.calls

import com.saathi.app.core.data.Settings
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.BackendClient
import com.saathi.app.core.net.CallLine
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class RealCallStart(
    @SerialName("patient_name") val patientName: String,
    val appointment: Appointment,
    @SerialName("reply_style") val replyStyle: String,
)

/** Live state of a real (Bolna) call, polled from `GET /calls/clinic/real/{id}`. */
@Serializable
data class RealCallState(
    val id: String,
    val status: String,
    val done: Boolean = false,
    val transcript: List<CallLine> = emptyList(),
    val confirmed: Boolean? = null,
    @SerialName("waiting_minutes") val waitingMinutes: Int? = null,
    val summary: String? = null,
    val error: String? = null,
)

/** Thrown with the backend's plain-language reason (e.g. Bolna not configured, number not allowed). */
class RealCallUnavailable(message: String) : Exception(message)

/**
 * Talks to the backend's real-call routes (backend `routers/telephony.py`). A Bolna voice agent dials the clinic
 * and runs the conversation; the app starts the call, polls the transcript and can hang up.
 */
class RealCallClient(private val settings: () -> Settings) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    suspend fun start(body: RealCallStart): RealCallState =
        send("/calls/clinic/real/start", SaathiJson.encodeToString(RealCallStart.serializer(), body))

    suspend fun poll(id: String): RealCallState = send("/calls/clinic/real/$id", null)

    suspend fun hangUp(id: String): RealCallState = send("/calls/clinic/real/$id/hangup", "{}")

    private suspend fun send(path: String, body: String?): RealCallState = withContext(Dispatchers.IO) {
        val s = settings()
        val req = Request.Builder().url(s.backendUrl.trimEnd('/') + path).header(BackendClient.DEVICE_HEADER, s.deviceId)
            .apply { if (body != null) post(body.toRequestBody(json)) }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RealCallUnavailable(detail(text) ?: "Server error ${resp.code}")
                SaathiJson.decodeFromString(RealCallState.serializer(), text)
            }
        } catch (e: IOException) {
            throw RealCallUnavailable("Can't reach the Saathi server.")
        }
    }

    /** FastAPI errors look like {"detail": "..."}. */
    private fun detail(text: String): String? =
        runCatching { SaathiJson.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }["detail"] }
            .getOrNull()?.toString()?.trim('"')
}
