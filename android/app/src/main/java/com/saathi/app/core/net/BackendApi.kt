package com.saathi.app.core.net

import java.io.File

/** Thrown when a backend feature is unavailable (503, timeout, offline) so callers use their fallback. */
class BackendUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the app asks of the backend. [BackendClient] talks HTTP; [FakeBackendClient] answers locally
 * so UI and flows work with no backend running (SPEC §9.2).
 */
interface BackendApi {
    suspend fun health(): Health

    /** The next appointment the doctor feature booked, from the backend's Meko-backed memory; null if none. */
    suspend fun storedAppointment(): Appointment? = null
    suspend fun stt(wav: File, languageHint: String, keyterms: List<String>): SttResult
    suspend fun tts(text: String, style: ReplyStyle): ByteArray
    suspend fun intent(utterance: String, style: ReplyStyle?): IntentResult
    suspend fun agentStep(request: AgentStepRequest): AgentAction
    suspend fun classify(items: List<ClassifyItem>): List<ClassifyResult>
    suspend fun extractAppointment(screenText: String): Appointment
    suspend fun digest(request: DigestRequest): String
    suspend fun checkLink(url: String): LinkVerdict
    suspend fun heartbeat(ts: Long)
    suspend fun welfareConfig(config: WelfareConfig)
    suspend fun welfareTest(): SendResult
    suspend fun notifyFamily(summary: String): SendResult

    /** One turn of the simulated clinic call (stretch): returns Saathi's next line. */
    suspend fun clinicCallTurn(request: ClinicCallRequest): ClinicCallTurn

    /** Reports how an agent run ended; the backend learns skills from successful runs. */
    suspend fun agentOutcome(report: AgentOutcomeReport)

    /** WebSocket URL for the camera Live relay, or null when the fake backend is in use. */
    fun liveCameraUrl(): String?

    /** WebSocket URL for the realtime voice conversation (Gemini Live relay), or null when unavailable. */
    fun liveVoiceUrl(): String?
}
