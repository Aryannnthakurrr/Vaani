package com.saathi.app.core

import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.net.IntentResult
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.net.Slots
import com.saathi.app.core.overlay.OverlayAgentUi
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.core.voice.LanguageStyle
import com.saathi.app.core.voice.LiveAgentUi
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

enum class AssistantState { IDLE, LISTENING, THINKING, RUNNING }

/**
 * The conversation front door: mic bubble / Home tiles → speech → intent → task, guide, camera or digest.
 * Only one run at a time; the Stop chip cancels it immediately (coroutine cancellation).
 */
class Assistant(private val c: AppContainer) {
    val speech = SpeechInput(c.context, { c.backend }, ::keyterms, { c.settings.current.replyStyle }, ::nameHints, ::localNames)
    private val runner = TaskRunner(c)
    private val guide = GuideMode(c, runner)

    /** Everything the assistant shows; the overlays and the in-app sheet draw it. */
    val screen = AssistantScreen()
    val ui = OverlayAgentUi(screen, c.speaker, speech) { c.settings.current.replyStyle }

    /** Set while a run has a live conversation open, so it keeps talking in real time (LiveAgentUi). */
    @Volatile private var liveUi: AgentUi? = null

    /**
     * The level every voice-wave animation follows, 0–1: the user's mic while Saathi listens, and a soft speech-like
     * wave while Saathi itself speaks (any live conversation, including the doctor intake, or the turn-based voice).
     */
    fun voiceLevel(): Float {
        val live = _live.value ?: c.care.live
        // The phone's own voice (a private value read on-device, a safety phrase) counts as speaking in a live call too.
        if (live != null) return if (live.state.value == LiveState.SPEAKING || c.speaker.speaking.value) speakingLevel() else live.micLevel.value
        return if (c.speaker.speaking.value) speakingLevel() else speech.level.value
    }

    private fun speakingLevel(): Float {
        val t = System.nanoTime() / 1e9
        return (0.45 + 0.2 * kotlin.math.sin(t * 5) * kotlin.math.sin(t * 1.3)).toFloat()
    }

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
    /** Id of the current run; a cancelled run that finishes late must not clear the screen of the next one. */
    private val runSeq = AtomicInteger(0)
    private val _taskActive = MutableStateFlow(false)
    /** A run (one exchange, task or guide) is going on: the talk button and the bubble mean Stop. */
    val taskActive: StateFlow<Boolean> = _taskActive
    /** Set when the user ends the conversation, so an end without it is reported as a dropped line. */
    @Volatile private var endRequested = false
    private val style get() = c.settings.current.replyStyle

    /** One status for the mic bubble and the talk button, from every voice source. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: StateFlow<VoiceStatus> = combine(
        _taskActive, _live.flatMapLatest { it?.state ?: flowOf(null) }, c.speaker.speaking, speech.listening,
    ) { busy, liveState, speaking, listening ->
        when {
            listening -> VoiceStatus.LISTENING
            liveState == LiveState.CONNECTING -> VoiceStatus.CONNECTING
            liveState == LiveState.SPEAKING || speaking -> VoiceStatus.SPEAKING
            liveState == LiveState.LISTENING -> VoiceStatus.LISTENING
            busy || liveState == LiveState.PAUSED -> VoiceStatus.WORKING
            else -> VoiceStatus.IDLE
        }
    }.stateIn(c.scope, SharingStarted.Eagerly, VoiceStatus.IDLE)

    /**
     * Mic bubble / "Talk to Vaani": opens a realtime voice conversation (Gemini Live via the backend). Tapping
     * again ends it; while a task runs, a tap stops the task. If Live is unavailable (offline, fake backend, socket
     * refused) the turn-based path is used.
     */
    fun onMicTapped() {
        // A running task is stopped first; the conversation, if one is open, carries on.
        if (job?.isActive == true) return stop()
        _live.value?.let { endLive(); return }
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
            languageLocked = { c.settings.current.language != "auto" },
        )
        endRequested = false
        _live.value = session
        _state.value = AssistantState.LISTENING
        OverlayService.start(c.context, OverlayService.ACTION_MIC_ON)
        // "Listening" only once the mic is really open; until then anything said would be lost.
        screen.caption(tr(style, "Connecting…", "जुड़ रही हूँ…", "Connect ho rahi hoon…"), "Vaani")
        liveWatch = c.scope.launch {
            launch {
                session.transcripts.collect { lines ->
                    val user = lines.lastOrNull { it.role == "user" }?.text
                    val saathi = lines.lastOrNull { it.role == "saathi" }?.text
                    // "English mein bolo" mid-conversation: the setting changes, and this conversation is told too (its
                    // language was fixed when it connected).
                    user?.let { heard ->
                        _lastHeard.value = heard
                        c.scope.launch {
                            if (switchLanguageIfAsked(heard, speak = false)) session.sendText(languageNote(c.settings.current.language))
                        }
                    }
                    // A running task owns the caption (its question, its Done button); the transcript waits.
                    if ((user != null || saathi != null) && job?.isActive != true) {
                        screen.caption(listOfNotNull(user?.let { "“$it”" }, saathi).joinToString("\n"), "Vaani")
                    }
                }
            }
            session.state.collect { ls ->
                if (job?.isActive == true) return@collect // a running task owns the state
                if (ls == LiveState.LISTENING && session.transcripts.value.isEmpty()) {
                    screen.caption(c.phrases.text("listening", style), "Vaani")
                }
                _state.value = when (ls) {
                    LiveState.CONNECTING, LiveState.LISTENING, LiveState.SPEAKING -> AssistantState.LISTENING
                    LiveState.PAUSED -> AssistantState.RUNNING
                    LiveState.ENDED, LiveState.ERROR -> AssistantState.IDLE
                }
            }
        }
        session.start()
    }

    /** Mutes or unmutes the user's mic in the open conversation (home or doctor intake) without ending it. */
    fun toggleMute() {
        val live = _live.value ?: c.care.live ?: return
        live.setMuted(!live.muted.value)
    }

    fun endLive() {
        endRequested = true
        _live.value?.end()
    }

    private suspend fun onLiveEnded(connected: Boolean) {
        liveWatch?.cancel()
        _live.value = null
        // A task that was talking through the conversation carries on with turn-based voice (RunUi picks it up).
        liveUi = null
        OverlayService.start(c.context, OverlayService.ACTION_MIC_OFF)
        if (job?.isActive != true) {
            screen.caption(null)
            _state.value = AssistantState.IDLE
        }
        when {
            // Never connected: fall back to one turn-based exchange so the tap still does something useful.
            !connected -> listenOnce()
            // Dropped without the user ending it: say so, instead of leaving them talking to nobody.
            !endRequested -> {
                val msg = tr(style, "The connection dropped. Tap the mic to talk again.", "कनेक्शन टूट गया। फिर से बात करने के लिए माइक दबाइए।",
                    "Connection toot gaya. Phir se baat karne ke liye mic dabaiye.")
                showResult(ok = false, msg)
                if (job?.isActive != true) c.speaker.say(msg, style)
            }
        }
    }

    /** One turn: record → transcribe → intent → act. Used when Live is unavailable. */
    private fun listenOnce() {
        if (c.context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val msg = tr(style, "I can't hear you: please allow the microphone for Vaani in Settings.",
                "मैं सुन नहीं पा रही: सेटिंग्स में वाणी को माइक की अनुमति दीजिए।",
                "Main sun nahi pa rahi: Settings mein Vaani ko mic ki permission dijiye.")
            showResult(ok = false, msg)
            c.scope.launch { c.speaker.say(msg, style) }
            return
        }
        launch { run ->
            _state.value = AssistantState.LISTENING
            OverlayService.start(c.context, OverlayService.ACTION_MIC_ON)
            screen.caption(c.phrases.text("listening", style), "Vaani")
            val heard = try { speech.listen(cue = true) } finally { OverlayService.start(c.context, OverlayService.ACTION_MIC_OFF) }
            if (heard == null || heard.transcript.isBlank()) {
                c.speaker.sayPhrase("didnt_understand", style)
                return@launch
            }
            handleUtterance(heard.transcript, heard.style, run)
        }
    }

    /** Typed or spoken request (also used by tests/demo buttons). */
    fun submitText(text: String) {
        // An open question or yes/no takes the text as its answer instead of starting something new.
        if (ui.offerAnswer(text)) return
        // An open conversation hears typed text exactly like speech.
        _live.value?.takeIf { job?.isActive != true }?.let { it.sendText(text); return }
        launch { run -> handleUtterance(text, LanguageStyle.detect(text), run) }
    }

    private suspend fun handleUtterance(text: String, heard: ReplyStyle, run: AgentUi) {
        _lastHeard.value = text
        val heardStyle = c.settings.current.autoStyle(heard)
        if (switchLanguageIfAsked(text)) return
        if (c.settings.current.language == "auto") c.settings.setLastStyle(heardStyle)
        _state.value = AssistantState.THINKING
        screen.caption("“$text”", tr(heardStyle, "Thinking…", "सोच रही हूँ…", "Soch rahi hoon…"))
        // A clear cab request ("cab to Saket") is routed on the phone at once; the cloud router can take 5-10 s.
        // Only a clean place name qualifies: "मुझे Saket" would not geocode, so the Uber deep link would be lost.
        val local = LocalIntentRules.route(text)
        val intent = local.takeIf { it.intent == "book_cab" && LocalIntentRules.cleanPlace(it.slots.destination) }
            ?: withTimeoutOrNull(6_000) { runCatching { c.backend.intent(text, heardStyle) }.getOrNull() } // Gemma takes ~3.5 s
            ?: local
        val replyStyle = c.settings.current.autoStyle(ReplyStyle.from(intent.replyStyle ?: heardStyle.wire))
        // The whole task then speaks in the language its first reply used, not switch after one line.
        if (c.settings.current.language == "auto") c.settings.setLastStyle(replyStyle)
        dispatch(intent, text, replyStyle, run)
    }

    private suspend fun dispatch(intent: IntentResult, utterance: String, replyStyle: ReplyStyle, run: AgentUi) {
        val reply = intent.replyText ?: LocalIntentRules.reply(intent.intent, intent.slots, replyStyle)
        val task = Tasks.forIntent(intent.intent)?.takeUnless { it == Tasks.PRACTO && !wantsPracto(intent.slots, utterance) }
        when {
            // The care flow takes the sentence as its first turn, so "send an ambulance now" acts at once.
            intent.intent == "book_doctor" && task == null -> startCare(utterance)
            task != null -> { run.say(reply); runTask(task, withAppointment(task, intent.slots, utterance), run, preferredApp(utterance)) }
            intent.intent == "guide" -> { run.say(reply); runGuide(utterance, run) }
            intent.intent == "care" -> startCare(utterance)
            intent.intent == "camera" -> { c.speaker.say(reply, replyStyle); com.saathi.app.features.camera.CameraSession.instructionSpoken(); openScreen("camera") }
            intent.intent == "digest" -> { openScreen("digest"); c.digest.speak() }
            intent.intent == "settings" -> { c.speaker.say(reply, replyStyle); openScreen("settings") }
            else -> run.say(reply)
        }
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
        busyJob() ?: launch(withLive = true) { run -> runTask(task, slots, run, preferredPkg) }

    fun startGuide(goal: String?): Job = busyJob() ?: launch(withLive = true) { run ->
        val g = goal ?: run.ask(tr(style, "What would you like to learn? For example: how to send a photo on WhatsApp.",
            "आप क्या सीखना चाहते हैं? जैसे: व्हाट्सऐप पर फ़ोटो कैसे भेजें।",
            "Aap kya seekhna chahte hain? Jaise: WhatsApp par photo kaise bhejein.")) ?: return@launch
        runGuide(g, run)
    }

    /** A second tap on a tile while something runs is ignored (it used to cancel and restart); Stop ends it. */
    private fun busyJob(): Job? = job?.takeIf { it.isActive }

    /**
     * "Cab to my appointment": the router never invents a place, so it leaves the destination empty and the cab app
     * fell back to a recent trip. The user's own words go in as the destination; TaskRunner turns them into the clinic.
     */
    private fun withAppointment(task: TaskSpec, slots: Slots, utterance: String): Slots =
        if (task == Tasks.UBER && slots.destination.isNullOrBlank() &&
            com.saathi.app.features.appointments.AppointmentState.refersToAppointment(utterance)) slots.copy(destination = utterance)
        else slots

    /** An app the user named in their request, e.g. "Flipkart se biscuit mangao". */
    private fun preferredApp(utterance: String): String? {
        val t = utterance.lowercase()
        return if ("flipkart" in t || "फ्लिपकार्ट" in t) com.saathi.app.util.Packages.FLIPKART else null
    }

    private suspend fun runTask(task: TaskSpec, initial: Slots, run: AgentUi, preferredPkg: String? = null) {
        if (c.care.running) c.care.stop() // a doctor conversation left open must not keep talking or calling
        _lastOutcome.value = null
        _state.value = AssistantState.RUNNING
        run.setRunning(true) // the Stop chip from the first question, not only once the app is open
        // From the live conversation too: the model may leave out "my appointment", but the user's last words have it.
        // Only while one is open: after a tile tap the last words may be from an older request.
        var slots = if (_live.value != null) withAppointment(task, initial, _lastHeard.value) else initial
        for (q in task.required) {
            if (q.isFilled(slots)) continue
            val answer = run.ask(q.ask(style)) ?: return
            slots = q.fill(slots, answer)
        }
        report(runner.run(task, slots, run, preferredPkg))
    }

    private suspend fun runGuide(goal: String, run: AgentUi) {
        if (c.care.running) c.care.stop()
        _lastOutcome.value = null
        _state.value = AssistantState.RUNNING
        run.setRunning(true)
        report(guide.run(goal, run))
    }

    private fun report(outcome: AgentOutcome) {
        _lastOutcome.value = outcome
        when (outcome) {
            is AgentOutcome.Done -> if (outcome.message.isNotBlank()) showResult(ok = true, outcome.message)
            is AgentOutcome.Failed -> {
                // Shown as well as spoken, so a failure doesn't vanish the moment the sentence ends.
                showResult(ok = false, outcome.message)
                c.notifier.post(Notifier.CH_ASSISTANT, 7, "Vaani", outcome.message)
            }
            AgentOutcome.Cancelled -> Unit
        }
    }

    /** The end-of-task card, over whatever app is in front (or in Saathi's own sheet); it goes after a few seconds. */
    private fun showResult(ok: Boolean, message: String) {
        val r = AssistantScreen.Result(ok, message)
        screen.result(r)
        c.scope.launch { delay(RESULT_MS); screen.clearResult(r) }
    }

    fun stop() {
        job?.cancel()
        c.speaker.stop()
        runSeq.incrementAndGet() // the cancelled run's own cleanup will see it is no longer current
        endRun()
        c.scope.launch { c.speaker.sayPhrase("stopped", style) }
    }

    /** Clears everything a run put on screen and goes back to the conversation if one is open, otherwise idle. */
    private fun endRun() {
        screen.clear()
        liveUi = null
        _taskActive.value = false
        _state.value = if (_live.value != null) AssistantState.LISTENING else AssistantState.IDLE
        if (_live.value != null) screen.caption(c.phrases.text("listening", style), "Vaani")
    }

    /**
     * Starts a run (one exchange, a task or a guide), cancelling any earlier one. The block gets a [RunUi] bound to
     * this run: it speaks through the live conversation while one is open ([withLive]), otherwise turn-based, and
     * goes quiet once a newer run has started. However the run ends, its screen is cleared here, once.
     */
    private fun launch(withLive: Boolean = false, block: suspend (AgentUi) -> Unit): Job {
        job?.cancel()
        val id = runSeq.incrementAndGet()
        _taskActive.value = true
        liveUi = if (withLive) _live.value?.let { LiveAgentUi(it, ui) } else null
        val run = RunUi(id)
        return c.scope.launch {
            try {
                block(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "run failed", e)
                report(AgentOutcome.Failed(tr(style, "Sorry, something went wrong. Please try again.",
                    "माफ़ कीजिए, कुछ गड़बड़ हो गई। कृपया फिर से कोशिश कीजिए।", "Sorry, kuch gadbad ho gayi. Please phir se try kijiye.")))
            } finally {
                if (runSeq.get() == id) endRun()
            }
        }.also { job = it }
    }

    /** The [AgentUi] of one run: live or turn-based at each call, and silent once the run is no longer current. */
    private inner class RunUi(private val id: Int) : AgentUi {
        private val current get() = runSeq.get() == id
        private val target: AgentUi get() = liveUi ?: ui
        private fun check() { if (!current) throw CancellationException("a newer run started") }

        override suspend fun say(text: String) { check(); target.say(text) }
        override suspend fun sayPhrase(key: String) { check(); target.sayPhrase(key) }
        override suspend fun ask(question: String): String? { check(); return target.ask(question) }
        override suspend fun confirm(summary: String): Boolean { check(); return target.confirm(summary) }
        override suspend fun waitForUserDone(prompt: String): Boolean { check(); return target.waitForUserDone(prompt) }
        override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) { if (current) target.showHighlight(bounds, instruction, gesture) }
        override fun clearHighlight() { if (current) target.clearHighlight() }
        override fun setRunning(running: Boolean) { if (current) target.setRunning(running) }
    }

    /** Opens one of Saathi's screens: straight through the NavHost when Saathi is in front, else by intent. */
    fun openScreen(route: String) = com.saathi.app.ui.Nav.open(c.context, route)

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

    /** The note that moves an open conversation to the language the user just asked for. */
    private fun languageNote(code: String): String = when (code) {
        "hi" -> "[The user asked you to speak only Hindi from now on. Reply only in simple Hindi, whatever language they use.]"
        else -> "[The user asked you to speak only English from now on. Reply only in simple English, whatever language they use.]"
    }

    /** Words Sarvam should listen out for: app names, family names, doctors from reminders. */
    /** Family contacts and the user's own name: the only names sent to Sarvam as hints. */
    private fun nameHints(): List<String> {
        val s = c.settings.current
        return (s.contacts.map { it.name } + listOf(s.userName)).filter { it.isNotBlank() }
    }

    @Volatile private var phoneBook: List<String> = emptyList()
    @Volatile private var phoneBookAt = 0L

    /** Every name the phone knows, refreshed every 10 minutes. Stays on the phone. */
    private fun localNames(): List<String> {
        val now = System.currentTimeMillis()
        if (now - phoneBookAt > 10 * 60_000L) {
            phoneBook = c.contacts.allNames()
            phoneBookAt = now
        }
        return nameHints() + phoneBook
    }

    private fun keyterms(): List<String> {
        val s = c.settings.current
        return listOf("Vaani", "Uber", "Practo", "Amazon", "Flipkart", "Pronto", "WhatsApp", "Apollo") +
            s.contacts.map { it.name } + listOf(s.userName).filter { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "SaathiAssistant"
        const val RESULT_MS = 6_000L
    }
}
