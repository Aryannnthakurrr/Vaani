package com.saathi.app.core.net

import com.saathi.app.core.agent.MockAgentScripts
import com.saathi.app.core.voice.LocalIntentRules
import com.saathi.app.features.calls.LocalClinicCall
import com.saathi.app.features.inbox.LocalClassifier
import com.saathi.app.features.inbox.LocalDigestWriter
import com.saathi.app.features.scam.LocalLinkRules
import java.io.File

/**
 * Answers every backend call on the device so the UI and flows work without a backend (SPEC §9.2).
 * Speech is reported unavailable, which routes voice to Android's on-device engines exactly like a network failure.
 */
class FakeBackendClient(
    private val scripts: () -> MockAgentScripts,
    private val userName: () -> String?,
) : BackendApi {

    override suspend fun health() = Health(status = "fake", mock = true)

    override suspend fun stt(wav: File, languageHint: String, keyterms: List<String>): SttResult =
        throw BackendUnavailable("fake backend: no cloud speech")

    override suspend fun tts(text: String, style: ReplyStyle): ByteArray =
        throw BackendUnavailable("fake backend: no cloud speech")

    override suspend fun intent(utterance: String, style: ReplyStyle?): IntentResult = LocalIntentRules.route(utterance)

    override suspend fun agentStep(request: AgentStepRequest): AgentAction = scripts().step(request)

    override suspend fun classify(items: List<ClassifyItem>): List<ClassifyResult> = items.map(LocalClassifier::classify)

    override suspend fun extractAppointment(screenText: String): Appointment = LocalClassifier.extractAppointment(screenText)

    override suspend fun digest(request: DigestRequest): String =
        LocalDigestWriter.write(request.items, ReplyStyle.from(request.replyStyle), request.userName ?: userName())

    override suspend fun checkLink(url: String): LinkVerdict = LocalLinkRules.verdict(url)

    override suspend fun heartbeat(ts: Long) = Unit

    override suspend fun welfareConfig(config: WelfareConfig) = Unit

    override suspend fun welfareTest() = SendResult(text = "Fake backend: welfare alert logged on the phone only.", logged = true)

    override suspend fun notifyFamily(summary: String) = SendResult(text = summary, logged = true)

    override suspend fun clinicCallTurn(request: ClinicCallRequest): ClinicCallTurn = LocalClinicCall.turn(request)

    override suspend fun agentOutcome(report: AgentOutcomeReport) = Unit

    override fun liveCameraUrl(): String? = null

    override fun liveVoiceUrl(): String? = null
}
