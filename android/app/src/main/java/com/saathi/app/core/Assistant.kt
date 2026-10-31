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

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state

    /** Text of the last thing the user said, for the Home screen. */
    private val _lastHeard = MutableStateFlow("")
    val lastHeard: StateFlow<String> = _lastHeard

    private var job: Job? = null
    private val style get() = c.settings.current.replyStyle

    fun onMicTapped() {
        if (job?.isActive == true) return stop()
        launch {
            _state.value = AssistantState.LISTENING
            OverlayService.start(c.context, OverlayService.ACTION_MIC_ON)
            OverlayService.controller?.showCaption(c.phrases.text("listening", style), "🎤")
            val heard = try { speech.listen() } finally { OverlayService.start(c.context, OverlayService.ACTION_MIC_OFF) }
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
        launch { handleUtterance(text, LanguageStyle.detect(text)) }
    }

    private suspend fun handleUtterance(text: String, heardStyle: ReplyStyle) {
        _lastHeard.value = text
        if (c.settings.current.language == "auto") c.settings.setLastStyle(heardStyle)
        _state.value = AssistantState.THINKING
        OverlayService.controller?.showCaption("“$text”", tr(heardStyle, "Thinking…", "सोच रहा हूँ…", "Soch raha hoon…"))
        val intent = withTimeoutOrNull(4_000) { runCatching { c.backend.intent(text, heardStyle) }.getOrNull() }
            ?: LocalIntentRules.route(text)
        dispatch(intent, text, ReplyStyle.from(intent.replyStyle ?: heardStyle.wire))
    }

    private suspend fun dispatch(intent: IntentResult, utterance: String, replyStyle: ReplyStyle) {
        val reply = intent.replyText ?: LocalIntentRules.reply(intent.intent, intent.slots, replyStyle)
        val task = Tasks.forIntent(intent.intent)
        when {
            task != null -> { ui.say(reply); runTask(task, intent.slots, preferredApp(utterance)) }
            intent.intent == "guide" -> { ui.say(reply); runGuide(utterance) }
            intent.intent == "camera" -> { c.speaker.say(reply, replyStyle); openScreen("camera") }
            intent.intent == "digest" -> { openScreen("digest"); c.digest.speak() }
            intent.intent == "settings" -> { c.speaker.say(reply, replyStyle); openScreen("settings") }
            else -> ui.say(reply)
        }
        OverlayService.controller?.hideCaption()
    }

    /** Starts a task from a Home tile: asks for any missing slot first. */
    fun startTask(task: TaskSpec, slots: Slots = Slots()) = launch { runTask(task, slots) }

    fun startGuide(goal: String?) = launch {
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
        var slots = initial
        for (q in task.required) {
            if (q.isFilled(slots)) continue
            val answer = ui.ask(q.ask(style)) ?: return
            slots = q.fill(slots, answer)
        }
        _state.value = AssistantState.RUNNING
        report(runner.run(task, slots, ui, preferredPkg))
    }

    private suspend fun runGuide(goal: String) {
        _state.value = AssistantState.RUNNING
        report(guide.run(goal, ui))
    }

    private fun report(outcome: AgentOutcome) {
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
            try { block() } finally { _state.value = AssistantState.IDLE }
        }.also { job = it }
    }

    private fun openScreen(route: String) {
        c.context.startActivity(Intent(c.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route))
    }

    /** Words Sarvam should listen out for: app names, family names, doctors from reminders. */
    private fun keyterms(): List<String> {
        val s = c.settings.current
        return listOf("Saathi", "Uber", "Practo", "Amazon", "Flipkart", "Pronto", "WhatsApp", "Apollo") +
            s.contacts.map { it.name } + listOf(s.userName).filter { it.isNotBlank() }
    }
}
