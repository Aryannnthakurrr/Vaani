package com.saathi.app.core.net

import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP implementation of [BackendApi]. Every call carries the X-Saathi-Device header. */
class BackendClient(
    private val baseUrl: () -> String,
    private val deviceId: () -> String,
    base: OkHttpClient = OkHttpClient(),
) : BackendApi {

    private val http = base.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val sttHttp = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val ttsHttp = http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val agentHttp = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val linkHttp = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = baseUrl().trimEnd('/') + path

    private fun request(path: String) = Request.Builder().url(url(path)).header(DEVICE_HEADER, deviceId())

    private suspend fun execute(client: OkHttpClient, req: Request): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BackendUnavailable("HTTP ${resp.code} for ${req.url.encodedPath}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw BackendUnavailable("network: ${e.message}", e)
        }
    }

    private suspend fun <Req, Res> post(
        path: String, body: Req, reqSer: KSerializer<Req>, resSer: KSerializer<Res>, client: OkHttpClient = http,
    ): Res {
        val payload = SaathiJson.encodeToString(reqSer, body).toRequestBody(jsonType)
        val bytes = execute(client, request(path).post(payload).build())
        return SaathiJson.decodeFromString(resSer, bytes.decodeToString())
    }

    override suspend fun health(): Health =
        SaathiJson.decodeFromString(Health.serializer(), execute(http, request("/health").get().build()).decodeToString())

    override suspend fun stt(wav: File, languageHint: String, keyterms: List<String>): SttResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "turn.wav", wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("language_hint", languageHint)
            .addFormDataPart("keyterms", keyterms.joinToString(","))
            .build()
        val bytes = execute(sttHttp, request("/voice/stt").post(body).build())
        return SaathiJson.decodeFromString(SttResult.serializer(), bytes.decodeToString())
    }

    override suspend fun tts(text: String, style: ReplyStyle): ByteArray {
        val payload = SaathiJson.encodeToString(TtsRequest.serializer(), TtsRequest(text, style.wire))
        return execute(ttsHttp, request("/voice/tts").post(payload.toRequestBody(jsonType)).build())
    }

    override suspend fun intent(utterance: String, style: ReplyStyle?): IntentResult =
        post("/intent", IntentRequest(utterance, style?.wire), IntentRequest.serializer(), IntentResult.serializer())

    override suspend fun agentStep(request: AgentStepRequest): AgentAction =
        post("/agent/step", request, AgentStepRequest.serializer(), AgentAction.serializer(), agentHttp)

    override suspend fun classify(items: List<ClassifyItem>): List<ClassifyResult> =
        post("/classify/messages", ClassifyRequest(items), ClassifyRequest.serializer(), ClassifyResponse.serializer())
            .results

    override suspend fun extractAppointment(screenText: String): Appointment =
        post("/extract/appointment", ExtractRequest(screenText), ExtractRequest.serializer(), Appointment.serializer())

    override suspend fun digest(request: DigestRequest): String =
        post("/digest", request, DigestRequest.serializer(), DigestResponse.serializer()).text

    override suspend fun checkLink(url: String): LinkVerdict =
        post("/links/check", LinkCheckRequest(url), LinkCheckRequest.serializer(), LinkVerdict.serializer(), linkHttp)

    override suspend fun heartbeat(ts: Long) {
        post("/welfare/heartbeat", Heartbeat(deviceId(), ts), Heartbeat.serializer(), serializer<JsonObject>())
    }

    override suspend fun welfareConfig(config: WelfareConfig) {
        post("/welfare/config", config, WelfareConfig.serializer(), serializer<JsonObject>())
    }

    override suspend fun welfareTest(): SendResult =
        post("/welfare/test", DeviceOnly(deviceId()), DeviceOnly.serializer(), SendResult.serializer())

    override suspend fun notifyFamily(summary: String): SendResult =
        post("/scam/notify_family", FamilyNotify(deviceId(), summary), FamilyNotify.serializer(), SendResult.serializer())

    override suspend fun clinicCallTurn(request: ClinicCallRequest): ClinicCallTurn =
        post("/calls/clinic/turn", request, ClinicCallRequest.serializer(), ClinicCallTurn.serializer(), agentHttp)

    override fun liveCameraUrl(): String {
        val ws = baseUrl().trimEnd('/').replaceFirst("http", "ws")
        return "$ws/live/camera?device=${deviceId()}"
    }

    companion object {
        const val DEVICE_HEADER = "X-Saathi-Device"
    }
}
