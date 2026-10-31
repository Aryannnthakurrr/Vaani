package com.saathi.app.core

import android.content.Intent
import com.saathi.app.MainActivity
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.net.IntentResult
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.net.Slots
import com.saathi.app.core.overlay.OverlayAgentUi
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.core.voice.LanguageStyle
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.LiveTools
import com.saathi.app.core.voice.LiveVoiceSession
import com.saathi.app.core.voice.LocalIntentRules
import com.saathi.app.core.voice.SpeechInput
import com.saathi.app.core.voice.tr
import com.saathi.app.features.guide.GuideMode
import com.saathi.app.features.tasks.TaskRunner
import com.saathi.app.features.tasks.TaskSpec
import com.saathi.app.features.tasks.Tasks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AssistantState { IDLE, LISTENING, THINKING, RUNNING }

/**
 * The conversation front door: mic bubble / Home tiles → speech → intent → task, guide, camera or digest.
 * Only one run at a time; the Stop chip cancels it immediately (coroutine cancellation).
 */
class Assistant(private val c: AppContainer) {
    val speech = SpeechInput(c.context, { c.backend }, ::keyterms, { c.settings.current.replyStyle })
    private val runner = TaskRunner(c)
    private val guide = GuideMode(c, runner)
    val ui = OverlayAgentUi({ OverlayService.controller }, c.speaker, speech, { c.settings.current.replyStyle }, ::stop)

    /** Set while a task started from the live conversation runs, so it keeps talking in real time (LiveAgentUi). */
    @Volatile var liveUi: com.saathi.app.core.agent.AgentUi? = null
    private val activeUi get() = liveUi ?: ui

    /** How loud the user is right now (live mic, else the turn-based mic), 0–1, for voice-wave animations. */
    fun voiceLevel(): Float = _live.value?.micLevel?.value ?: speech.level.value

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state

    /** Text of the last thing the user said, for the Home screen. */
    private val _lastHeard = MutableStateFlow("")
    val lastHeard: StateFlow<String> = _lastHeard

    /** How the last task or guide ended; Live tools report it back to the conversation. */
    private val _lastOutcome = MutableStateFlow<AgentOutcome?>(null)
    val lastOutcome: StateFlow<AgentOutcome?> = _lastOutcome

    /** The realtime conversation, while one is open. */
    private val _live = MutableStateFlow<LiveVoiceSession?>(null)
    val live: StateFlow<LiveVoiceSession?> = _live
    private var liveWatch: Job? = null

    private var job: Job? = null
    private val style get() = c.settings.current.replyStyle

    /**
     * Mic bubble / "Talk to Saathi": opens a realtime voice conversation (Gemini Live via the backend). Tapping
     * again ends it. If Live is unavailable (offline, fake backend, socket refused) the turn-based path is used.
     */
    fun onMicTapped() {
        _live.value?.let { endLive(); return }
        if (job?.isActive == true) return stop()
        val url = c.backend.liveVoiceUrl() ?: return listenOnce()
        startLive(url)
    }

    private fun startLive(url: String) {
        val session = LiveVoiceSession(
            context = c.context,
            url = url,
            replyStyle = { style.wire },
            tools = LiveTools(c) { _live.value },
            onEnded = { connected -> c.scope.launch { onLiveEnded(connected) } },
            sharedContext = {
                val appt = c.settings.current.lastAppointment?.let {
                    runCatching { com.saathi.app.util.SaathiJson.decodeFromString(com.saathi.app.core.net.Appointment.serializer(), it) }.getOrNull()
                }
                listOfNotNull(
                    c.profiles.contextLine(),
                    com.saathi.app.features.appointments.AppointmentState.contextLine(appt, java.time.ZonedDateTime.now()),
                ).joinToString("\n").ifBlank { null }
            },
        )
        _live.value = session
        _state.value = AssistantState.LISTENING
        OverlayService.start(c.context, OverlayService.ACTION_MIC_ON)
        OverlayService.controller?.showCaption(c.phrases.text("listening", style), "Saathi")
        liveWatch = c.scope.launch {
            launch {
                session.transcripts.collect { lines ->
                    val user = lines.lastOrNull { it.role == "user" }?.text
                    val saathi = lines.lastOrNull { it.role == "saathi" }?.text
                    user?.let { _lastHeard.value = it; c.scope.launch { switchLanguageIfAsked(it, speak = false) } }
                    if (user != null || saathi != null) {
                        OverlayService.controller?.showCaption(listOfNotNull(user?.let { "“$it”" }, saathi).joinToString("\n"), "Saathi")
                    }
                }
            }
            session.state.collect { ls ->
                if (job?.isActive == true) return@collect // a running task owns the state
                _state.value = when (ls) {
                    LiveState.CONNECTING, LiveState.LISTENING, LiveState.SPEAKING -> AssistantState.LISTENING
                    LiveState.PAUSED -> AssistantState.RUNNING
                    LiveState.ENDED, LiveState.ERROR -> AssistantState.IDLE
                }
            }
        }
        session.start()
    }

    fun endLive() {
        _live.value?.end()
    }

    private fun onLiveEnded(connected: Boolean) {
        liveWatch?.cancel()
        _live.value = null
        OverlayService.start(c.context, OverlayService.ACTION_MIC_OFF)
        OverlayService.controller?.hideCaption()
        if (job?.isActive != true) _state.value = AssistantState.IDLE
        // Never connected: fall back to one turn-based exchange so the tap still does something useful.
        if (!connected) listenOnce()
    }

    /** One turn: record → transcribe → intent → act. Used when Live is unavailable. */
    private fun listenOnce() {
        launch {
            _state.value = AssistantState.LISTENING
            OverlayService.start(c.context, OverlayService.ACTION_MIC_ON)
            OverlayService.controller?.showCaption(c.phrases.text("listening", style), "Saathi")
            val heard = try { speech.listen(cue = true) } finally { OverlayService.start(c.context, OverlayService.ACTION_MIC_OFF) }
            if (heard == null || heard.transcript.isBlank()) {
                c.speaker.sayPhrase("didnt_understand", style)
                OverlayService.controller?.hideCaption()
                return@launch
            }
            handleUtterance(heard.transcript, heard.style)
        }
    }

    /** Typed or spoken request (also used by tests/demo buttons). */
    fun submitText(text: String) {
        // An open question or yes/no takes the text as its answer instead of starting something new.
        if (ui.offerAnswer(text)) return
        // An open conversation hears typed text exactly like speech.
        _live.value?.takeIf { job?.isActive != true }?.let { it.sendText(text); return }
        launch { handleUtterance(text, LanguageStyle.detect(text)) }
    }

    private suspend fun handleUtterance(text: String, heard: ReplyStyle) {
        _lastHeard.value = text
        val heardStyle = c.settings.current.autoStyle(heard)
        if (switchLanguageIfAsked(text)) return
        if (c.settings.current.language == "auto") c.settings.setLastStyle(heardStyle)
        _state.value = AssistantState.THINKING
        OverlayService.controller?.showCaption("“$text”", tr(heardStyle, "Thinking…", "सोच रही हूँ…", "Soch rahi hoon…"))
        // A clear cab request ("cab to Saket") is routed on the phone at once; the cloud router can take 5-10 s.
        // Only a clean place name qualifies: "मुझे Saket" would not geocode, so the Uber deep link would be lost.
        val local = LocalIntentRules.route(text)
        val intent = local.takeIf { it.intent == "book_cab" && LocalIntentRules.cleanPlace(it.slots.destination) }
            ?: withTimeoutOrNull(6_000) { runCatching { c.backend.intent(text, heardStyle) }.getOrNull() } // Gemma takes ~3.5 s
            ?: local
        dispatch(intent, text, c.settings.current.autoStyle(ReplyStyle.from(intent.replyStyle ?: heardStyle.wire)))
    }

    private suspend fun dispatch(intent: IntentResult, utterance: String, replyStyle: ReplyStyle) {
        val reply = intent.replyText ?: LocalIntentRules.reply(intent.intent, intent.slots, replyStyle)
        val task = Tasks.forIntent(intent.intent)?.takeUnless { it == Tasks.PRACTO && !wantsPracto(intent.slots, utterance) }
        when {
            // The care flow takes the sentence as its first turn, so "send an ambulance now" acts at once.
            intent.intent == "book_doctor" && task == null -> startCare(utterance)
            task != null -> { ui.say(reply); runTask(task, intent.slots, preferredApp(utterance)) }
            intent.intent == "guide" -> { ui.say(reply); runGuide(utterance) }
            intent.intent == "care" -> startCare(utterance)
            intent.intent == "camera" -> { c.speaker.say(reply, replyStyle); openScreen("camera") }
            intent.intent == "digest" -> { openScreen("digest"); c.digest.speak() }
            intent.intent == "settings" -> { c.speaker.say(reply, replyStyle); openScreen("settings") }
            else -> ui.say(reply)
        }
        OverlayService.controller?.hideCaption()
    }

    /** The symptom-to-care flow (docs/CARE.md): what "I need a doctor" means unless Practo or a named doctor was asked for. */
    fun startCare(firstWords: String? = null) {
        c.care.start(firstWords)
        openScreen("care")
    }

    fun wantsPracto(slots: Slots, utterance: String?) =
        !slots.doctor.isNullOrBlank() || utterance?.contains("practo", ignoreCase = true) == true

    /** Starts a task from a Home tile or a Live tool call: asks for any missing slot first. */
    fun startTask(task: TaskSpec, slots: Slots = Slots(), preferredPkg: String? = null): Job =
        launch { runTask(task, slots, preferredPkg) }

    fun startGuide(goal: String?): Job = launch {
        val g = goal ?: ui.ask(tr(style, "What would you like to learn? For example: how to send a photo on WhatsApp.",
            "आप क्या सीखना चाहते हैं? जैसे: व्हाट्सऐप पर फ़ोटो कैसे भेजें।",
            "Aap kya seekhna chahte hain? Jaise: WhatsApp par photo kaise bhejein.")) ?: return@launch
        runGuide(g)
    }

    /** An app the user named in their request, e.g. "Flipkart se biscuit mangao". */
    private fun preferredApp(utterance: String): String? {
        val t = utterance.lowercase()
        return if ("flipkart" in t || "फ्लिपकार्ट" in t) com.saathi.app.util.Packages.FLIPKART else null
    }

    private suspend fun runTask(task: TaskSpec, initial: Slots, preferredPkg: String? = null) {
        _lastOutcome.value = null
        var slots = initial
        for (q in task.required) {
            if (q.isFilled(slots)) continue
            val answer = activeUi.ask(q.ask(style)) ?: return
            slots = q.fill(slots, answer)
        }
        _state.value = AssistantState.RUNNING
        report(runner.run(task, slots, activeUi, preferredPkg))
    }

    private suspend fun runGuide(goal: String) {
        _lastOutcome.value = null
        _state.value = AssistantState.RUNNING
        report(guide.run(goal, activeUi))
    }

    private fun report(outcome: AgentOutcome) {
        _lastOutcome.value = outcome
        if (outcome is AgentOutcome.Done && outcome.message.isNotBlank()) OverlayService.controller?.showSuccess(outcome.message)
        if (outcome is AgentOutcome.Failed) {
            c.notifier.post(Notifier.CH_ASSISTANT, 7, "Saathi", outcome.message)
        }
    }

    fun stop() {
        job?.cancel()
        c.speaker.stop()
        OverlayService.controller?.let { it.hideStop(); it.hideCaption(); it.clearHighlight() }
        _state.value = AssistantState.IDLE
        c.scope.launch { c.speaker.sayPhrase("stopped", style) }
    }

    private fun launch(block: suspend () -> Unit): Job {
        job?.cancel()
        return c.scope.launch {
            try { block() } finally {
                // Back to the open conversation if there is one, otherwise idle.
                _state.value = if (_live.value != null) AssistantState.LISTENING else AssistantState.IDLE
            }
        }.also { job = it }
    }

    fun openScreen(route: String) {
        c.context.startActivity(Intent(c.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route))
    }

    /**
     * "Mujhe English nahi aati" / "speak English": saves the language for good, so every screen and reply follows.
     * In a live conversation Gemini answers the sentence itself, so only the setting changes there.
     */
    private suspend fun switchLanguageIfAsked(text: String, speak: Boolean = true): Boolean {
        val code = com.saathi.app.core.voice.LanguageSwitch.detect(text) ?: return false
        if (c.settings.current.language == code) return false
        c.settings.setLanguage(code)
        if (speak) c.speaker.say(com.saathi.app.core.voice.LanguageSwitch.confirmation(code), ReplyStyle.from(code))
        return true
    }

    /** Words Sarvam should listen out for: app names, family names, doctors from reminders. */
    private fun keyterms(): List<String> {
        val s = c.settings.current
        return listOf("Saathi", "Uber", "Practo", "Amazon", "Flipkart", "Pronto", "WhatsApp", "Apollo") +
            s.contacts.map { it.name } + listOf(s.userName).filter { it.isNotBlank() }
    }
}
