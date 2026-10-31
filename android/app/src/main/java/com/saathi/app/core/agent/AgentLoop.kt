package com.saathi.app.core.agent

import com.saathi.app.core.data.ActionLogEntity
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.AgentOutcomeReport
import com.saathi.app.core.net.AgentStepRequest
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.HistoryItem
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.util.Packages
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** What a task adds to the loop: which scripted scenario/app it is and which deep links it built itself. */
data class TaskContext(
    val taskHint: String? = null,
    val trustedDeeplinks: Set<String> = emptySet(),
    val allowedPackages: Set<String> = Packages.ACT_ALLOWLIST,
    /** The app the task runs in; if it slips into the background (e.g. while the user answers), it is reopened. */
    val appPackage: String? = null,
)

sealed interface AgentOutcome {
    data class Done(val message: String, val lastScreen: ScreenSnapshot?) : AgentOutcome
    data class Failed(val message: String) : AgentOutcome
    data object Cancelled : AgentOutcome
}

/**
 * The observe → decide → validate → act loop (SPEC §6.3). One instance per run.
 * Every action is validated by [SafetyPolicy] in code before it reaches the [ActionPerformer].
 */
class AgentLoop(
    private val backend: () -> BackendApi,
    private val screen: ScreenSource,
    private val performer: ActionPerformer,
    private val ui: AgentUi,
    private val style: () -> ReplyStyle,
    private val log: suspend (ActionLogEntity) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val history = mutableListOf<HistoryItem>()

    /** One id for the whole run: every step and the final outcome carry it (docs/LEARNING.md §1). */
    val runId: String = UUID.randomUUID().toString()

    suspend fun run(goal: String, mode: AgentMode, task: TaskContext = TaskContext()): AgentOutcome {
        ui.setRunning(true)
        try {
            val outcome = loop(goal, mode, task)
            reportOutcome(goal, task, outcome)
            return outcome
        } catch (e: CancellationException) {
            logLine(goal, AgentAction("stop", reason = "user pressed stop"), "-", stoppedByYou(), "cancelled")
            withContext(NonCancellable) { reportOutcome(goal, task, AgentOutcome.Cancelled) }
            throw e
        } finally {
            ui.clearHighlight()
            ui.setRunning(false)
        }
    }

    /** Best effort: a lost report only means the backend learns one run fewer. Never blocks the user. */
    private suspend fun reportOutcome(goal: String, task: TaskContext, outcome: AgentOutcome) {
        val (kind, message) = when (outcome) {
            is AgentOutcome.Done -> "done" to outcome.message
            is AgentOutcome.Failed -> "failed" to outcome.message
            AgentOutcome.Cancelled -> "cancelled" to null
        }
        runCatching {
            withTimeoutOrNull(OUTCOME_TIMEOUT_MS) {
                backend().agentOutcome(AgentOutcomeReport(runId, task.taskHint, goal, kind, message))
            }
        }
    }

    private suspend fun loop(goal: String, mode: AgentMode, task: TaskContext): AgentOutcome {
        val seen = HashMap<String, Int>()
        var reopened = 0
        var invalidInARow = 0
        var last: ScreenSnapshot? = null
        for (step in 1..MAX_STEPS) {
            coroutineContext.ensureActive()
            val snap = screen.read().also { last = it }

            when (SafetyPolicy.preCheck(snap, mode, task.allowedPackages)) {
                ScreenStop.SECRET_ENTRY -> {
                    ui.sayPhrase("pin_screen")
                    if (!ui.waitForUserDone(s("Say \"done\" when you have finished.", "हो जाए तो \"हो गया\" बोलिए।", "Ho jaye to \"ho gaya\" boliye."))) {
                        return AgentOutcome.Cancelled
                    }
                    continue
                }
                ScreenStop.LOGIN -> {
                    ui.sayPhrase("login_screen")
                    if (!ui.waitForUserDone(s("Say \"done\" after logging in.", "लॉग इन के बाद \"हो गया\" बोलिए।", "Login ke baad \"ho gaya\" boliye."))) {
                        return AgentOutcome.Cancelled
                    }
                    continue
                }
                ScreenStop.FOREIGN_APP -> {
                    // The task's app went to the background (home screen, Saathi's own window): bring it back to the
                    // same screen and carry on, a couple of times at most, instead of giving up.
                    val app = task.appPackage
                    if (app != null && reopened < MAX_REOPEN) {
                        reopened++
                        val reopen = AgentAction(AgentAction.OPEN_APP, pkg = app, reason = "back to the task's app")
                        if (SafetyPolicy.validate(reopen, snap, mode, false, task.trustedDeeplinks, task.allowedPackages) is Verdict.Allow) {
                            performer.perform(reopen, snap)
                            delay(1_200)
                            performer.awaitSettle()
                            continue
                        }
                    }
                    val msg = s("I can't work inside ${Packages.displayName(snap.pkg)}. I have stopped.",
                        "मैं ${Packages.displayName(snap.pkg)} के अंदर काम नहीं कर सकती। मैंने रोक दिया है।",
                        "Main ${Packages.displayName(snap.pkg)} ke andar kaam nahi kar sakti. Maine rok diya hai.")
                    return fail(goal, snap, msg)
                }
                null -> Unit
            }

            val fp = TreeSerializer.fingerprint(snap)
            val repeats = (seen[fp] ?: 0) + 1
            seen[fp] = repeats
            if (repeats > MAX_SAME_SCREEN) {
                return fail(goal, snap, s("I'm stuck on this screen. Shall I show you how to do it instead?",
                    "मैं इस स्क्रीन पर अटक गई हूँ। क्या मैं आपको तरीका दिखाऊँ?",
                    "Main is screen par atak gayi hoon. Kya main aapko tareeka dikhaun?"))
            }

            val action = nextAction(goal, mode, snap, task)
            if (!ActionParser.isComplete(action) || action.action !in AgentAction.ALL) {
                if (++invalidInARow >= 2) return fail(goal, snap, s("I got confused. Shall I show you instead?",
                    "मैं उलझ गई। क्या मैं आपको दिखाऊँ?", "Main confuse ho gayi. Kya main aapko dikhaun?"))
                continue
            }
            invalidInARow = 0

            if (mode == AgentMode.ACT && step % 3 == 0) ui.say(progressLine(action))

            when (val result = handle(goal, mode, task, snap, action)) {
                null -> Unit
                else -> return result
            }
        }
        return fail(goal, last, s("This is taking too long, so I stopped. Shall I show you instead?",
            "इसमें बहुत समय लग रहा है, इसलिए मैंने रोक दिया। क्या मैं आपको दिखाऊँ?",
            "Isme bahut time lag raha hai, isliye maine rok diya. Kya main aapko dikhaun?"))
    }

    /** Executes one action. Returns an outcome when the run ends, null to keep looping. */
    private suspend fun handle(goal: String, mode: AgentMode, task: TaskContext, snap: ScreenSnapshot, action: AgentAction): AgentOutcome? {
        when (action.action) {
            AgentAction.DONE -> {
                val msg = action.message.orEmpty()
                logLine(goal, action, snap.pkg, s("Finished: $msg", "पूरा हुआ: $msg", "Poora hua: $msg"), "done")
                ui.say(msg)
                return AgentOutcome.Done(msg, snap)
            }
            AgentAction.FAIL -> return fail(goal, snap, action.message.orEmpty())
            AgentAction.ASK_USER -> {
                val answer = ui.ask(action.question.orEmpty()) ?: return cancelled(goal, snap)
                remember(action, "user answered: $answer")
                logLine(goal, action, snap.pkg, s("Asked you: ${action.question}", "आपसे पूछा: ${action.question}", "Aapse poocha: ${action.question}"), "ok")
                return null
            }
            AgentAction.WAIT -> {
                delay((action.ms ?: 1000).coerceIn(100, 5000))
                remember(action)
                return null
            }
            AgentAction.HIGHLIGHT -> {
                // "Swipe up" while the app the user wants is already visible: point at it instead.
                val app = if (action.id == null) GuideTarget.app(goal) else null
                val shortcut = app?.let { GuideTarget.visible(it, snap) }
                val node = shortcut ?: snap.node(action.id)
                val instruction = if (shortcut != null) {
                    s("Tap the $app icon.", "$app वाले आइकन पर टैप कीजिए।", "$app wale icon par tap kijiye.")
                } else action.instruction.orEmpty()
                ui.showHighlight(node?.let { currentBounds(it) }, instruction, if (shortcut != null) null else action.gesture)
                logLine(goal, action, snap.pkg, s("Showed you: $instruction", "आपको दिखाया: $instruction", "Aapko dikhaya: $instruction"), "ok")
                if (!sayAndAwaitStep(instruction)) {
                    sayAndAwaitStep(s("Take your time. ", "आराम से कीजिए। ", "Aaram se kijiye. ") + instruction)
                }
                ui.clearHighlight()
                // A swipe keeps the list moving for a moment; reading the screen mid-fling gives stale positions
                // and the next ring lands on the wrong row.
                performer.awaitSettle()
                remember(action)
                screen.onActionExecuted(action)
                return null
            }
            AgentAction.CONFIRM -> {
                val verdict = SafetyPolicy.validate(action, snap, mode, false, task.trustedDeeplinks, task.allowedPackages)
                if (verdict is Verdict.Block) return blocked(goal, snap, action, verdict.why)
                return confirmThen(goal, mode, task, snap, action.summary.orEmpty(), action.then!!, action)
            }
        }

        return when (val verdict = SafetyPolicy.validate(action, snap, mode, false, task.trustedDeeplinks, task.allowedPackages)) {
            is Verdict.Block -> blocked(goal, snap, action, verdict.why)
            is Verdict.NeedsConfirm -> {
                // The model tried to press a commit button directly: the gate turns it into a spoken yes/no.
                val summary = s("Shall I press \"${verdict.target}\"?", "क्या मैं \"${verdict.target}\" दबाऊँ?",
                    "Kya main \"${verdict.target}\" dabaun?")
                confirmThen(goal, mode, task, snap, summary, action, action)
            }
            Verdict.Allow -> execute(goal, snap, action)
        }
    }

    private suspend fun confirmThen(
        goal: String, mode: AgentMode, task: TaskContext, snap: ScreenSnapshot,
        summary: String, inner: AgentAction, original: AgentAction,
    ): AgentOutcome? {
        val yes = ui.confirm(summary)
        // Logged as a confirm step even when the gate made the question, so the log shows it as a question.
        val asked = if (original.action == AgentAction.CONFIRM) original
            else AgentAction(AgentAction.CONFIRM, summary = summary, then = original, reason = original.reason)
        logLine(goal, asked, snap.pkg, if (yes) s("Asked you: $summary — you said yes", "आपसे पूछा: $summary — आपने हाँ कहा", "Aapse poocha: $summary — aapne haan kaha")
            else s("Asked you: $summary — you said no", "आपसे पूछा: $summary — आपने ना कहा", "Aapse poocha: $summary — aapne na kaha"),
            if (yes) "ok" else "cancelled")
        if (!yes) {
            ui.sayPhrase("confirm_no_ack")
            return AgentOutcome.Cancelled
        }
        val v = SafetyPolicy.validate(inner, snap, mode, confirmed = true, task.trustedDeeplinks, task.allowedPackages)
        if (v is Verdict.Block) return blocked(goal, snap, inner, v.why)
        ui.sayPhrase("confirm_yes_ack")
        return execute(goal, snap, inner, recorded = original)
    }

    private suspend fun execute(goal: String, snap: ScreenSnapshot, action: AgentAction, recorded: AgentAction = action): AgentOutcome? {
        val ok = performer.perform(action, snap)
        logLine(goal, action, snap.pkg, describe(action, snap), if (ok) "ok" else "failed")
        remember(recorded, if (ok) action.reason else "could not perform ${action.action}")
        if (ok) {
            performer.awaitSettle()
            screen.onActionExecuted(action)
        }
        return null
    }

    private suspend fun nextAction(goal: String, mode: AgentMode, snap: ScreenSnapshot, task: TaskContext): AgentAction {
        val request = AgentStepRequest(
            goal = goal, mode = mode.name, language = style().wire,
            screenText = listOfNotNull(snap.screenshotNote, TreeSerializer.serialize(snap)).joinToString("\n"), pkg = snap.pkg,
            screenshotB64 = snap.screenshotB64, history = history.takeLast(HISTORY), taskHint = task.taskHint,
            runId = runId,
        )
        return withSlowCue { nextActionWithRetry(request) }
    }

    /**
     * Says "one moment" when the model takes longer than [SLOW_STEP_MS], so someone who can't see the "Thinking…"
     * caption knows Saathi is still working. A cue that has started is allowed to finish before the next line.
     */
    private suspend fun <T> withSlowCue(block: suspend () -> T): T = coroutineScope {
        var speaking = false
        val cue = launch {
            delay(SLOW_STEP_MS)
            speaking = true
            ui.sayPhrase("filler")
        }
        try {
            block()
        } finally {
            if (speaking && isActive) cue.join() else cue.cancel()
        }
    }

    /**
     * Guide mode: speaks the instruction while already watching for the user to do it, and returns as soon as they
     * have (a tap, a new window, or the screen changing and staying changed), cutting the speech short. Listening only
     * after the instruction finished used to miss taps made while Saathi was still talking, so it repeated "tap …"
     * for 30 s on a screen the user had already left. False when nothing happened within [GUIDE_WAIT_MS].
     */
    private suspend fun sayAndAwaitStep(instruction: String): Boolean = coroutineScope {
        val before = screen.fingerprint()
        val byEvent = async(start = CoroutineStart.UNDISPATCHED) { performer.awaitUserInteraction(GUIDE_WAIT_MS) }
        // No fingerprint (replay): only the interaction event decides.
        val byScreen = async { if (before == null) awaitCancellation() else screenMovedOn(before) }
        val speech = launch { ui.say(instruction) }
        val done = select {
            byEvent.onAwait { it }
            byScreen.onAwait { it }
        }
        byEvent.cancel()
        byScreen.cancel()
        if (done) speech.cancel() else speech.join()
        done
    }

    /** True once the screen differs from [before] on two polls in a row (one blip, like a clock tick, isn't enough). */
    private suspend fun screenMovedOn(before: String): Boolean = withTimeoutOrNull(GUIDE_WAIT_MS) {
        var previous: String? = null
        var moved = false
        while (!moved) {
            delay(GUIDE_POLL_MS)
            val now = screen.fingerprint() ?: continue
            moved = now != before && now == previous
            previous = now
        }
        moved
    } ?: false

    /** One quiet retry: a single slow or dropped model call should not end the user's task. */
    private suspend fun nextActionWithRetry(request: AgentStepRequest): AgentAction {
        return try {
            backend().agentStep(request)
        } catch (e: CancellationException) {
            throw e
        } catch (first: Exception) {
            try {
                backend().agentStep(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentAction.fail(s("I can't reach my helper right now. Shall I try again later?",
                    "अभी मैं अपने सर्वर तक नहीं पहुँच पा रही। क्या बाद में फिर कोशिश करें?",
                    "Abhi main server tak nahi pahunch pa rahi. Kya baad mein try karein?"), "network: ${e.message}")
            }
        }
    }

    /**
     * The model may take seconds to answer while a list is still easing to a stop, so the ring is placed using the
     * element's position on the screen as it is now (matched by label and type), not as it was when we asked.
     */
    private suspend fun currentBounds(target: ScreenNode): List<Int> {
        val now = runCatching { screen.read() }.getOrNull() ?: return target.bounds
        val same = now.nodes.filter { it.cls == target.cls && it.label == target.label && it.label.isNotEmpty() }
        return same.minByOrNull { kotlin.math.abs(it.bounds[1] - target.bounds[1]) }?.bounds ?: target.bounds
    }

    private fun remember(action: AgentAction, reason: String? = action.reason) {
        history += HistoryItem(action, reason)
    }

    private suspend fun blocked(goal: String, snap: ScreenSnapshot, action: AgentAction, why: String): AgentOutcome? {
        logLine(goal, action, snap.pkg, s("Blocked for safety: $why", "सुरक्षा के लिए रोका: $why", "Safety ke liye roka: $why"), "blocked")
        remember(action, "BLOCKED by safety policy: $why")
        return null
    }

    private suspend fun fail(goal: String, snap: ScreenSnapshot?, message: String): AgentOutcome {
        logLine(goal, AgentAction.fail(message), snap?.pkg ?: "-", s("Stopped: $message", "रुक गई: $message", "Ruk gayi: $message"), "failed")
        ui.say(message)
        return AgentOutcome.Failed(message)
    }

    private suspend fun cancelled(goal: String, snap: ScreenSnapshot): AgentOutcome {
        logLine(goal, AgentAction("stop"), snap.pkg, stoppedByYou(), "cancelled")
        return AgentOutcome.Cancelled
    }

    private fun stoppedByYou() = s("Stopped by you", "आपने रोका", "Aapne roka")

    /**
     * Stores the action and reason — never the raw screen text (SPEC §6.3). [plain] is written in the language in use
     * at the time, since the table keeps one line per step.
     */
    private suspend fun logLine(goal: String, action: AgentAction, pkg: String, plain: String, outcome: String) {
        runCatching {
            val redacted = action.copy(text = action.text?.let { "…" }, then = action.then?.let { it.copy(text = it.text?.let { "…" }) })
            log(ActionLogEntity(ts = clock(), goal = goal, actionJson = SaathiJson.encodeToString(AgentAction.serializer(), redacted),
                reason = action.reason, pkg = pkg, plain = plain, outcome = outcome))
        }
    }

    private fun describe(a: AgentAction, snap: ScreenSnapshot): String {
        val app = Packages.displayName(snap.pkg)
        val target = snap.node(a.id)?.label?.take(40)
        return when (a.action) {
            AgentAction.TAP -> if (target != null) s("Tapped \"$target\" in $app", "$app में \"$target\" दबाया", "$app mein \"$target\" dabaya")
                else s("Tapped an item in $app", "$app में एक चीज़ दबाई", "$app mein ek cheez dabayi")
            AgentAction.TAP_XY -> s("Tapped the screen in $app", "$app में स्क्रीन पर दबाया", "$app mein screen par dabaya")
            AgentAction.TYPE -> s("Typed \"${a.text}\" in $app", "$app में \"${a.text}\" लिखा", "$app mein \"${a.text}\" likha")
            AgentAction.SCROLL -> when (a.direction) {
                "up" -> s("Scrolled up in $app", "$app में ऊपर खिसकाया", "$app mein upar scroll kiya")
                "down" -> s("Scrolled down in $app", "$app में नीचे खिसकाया", "$app mein neeche scroll kiya")
                else -> s("Scrolled in $app", "$app में स्क्रॉल किया", "$app mein scroll kiya")
            }
            AgentAction.BACK -> s("Went back", "पीछे गई", "Peeche gayi")
            AgentAction.HOME -> s("Went to the home screen", "होम स्क्रीन पर गई", "Home screen par gayi")
            AgentAction.OPEN_APP -> Packages.displayName(a.pkg.orEmpty()).let { s("Opened $it", "$it खोला", "$it khola") }
            AgentAction.OPEN_DEEPLINK -> s("Opened $app with the trip details", "सफ़र की जानकारी के साथ $app खोला", "Trip details ke saath $app khola")
            else -> a.action
        }
    }

    private fun progressLine(a: AgentAction): String = when (a.action) {
        AgentAction.TYPE -> s("Searching for ${a.text}…", "${a.text} ढूँढ रही हूँ…", "${a.text} search kar rahi hoon…")
        else -> s("Still working on it…", "काम चल रहा है…", "Kaam chal raha hai…")
    }

    private fun s(en: String, hi: String, hinglish: String) = tr(style(), en, hi, hinglish)

    companion object {
        const val MAX_STEPS = 25
        const val MAX_SAME_SCREEN = 3
        const val HISTORY = 6
        const val GUIDE_WAIT_MS = 30_000L
        const val GUIDE_POLL_MS = 600L
        const val OUTCOME_TIMEOUT_MS = 5_000L
        /** Gemini answers in 2–3 s when healthy and 10–20 s under load (NOTES, 2026-10-31). */
        const val SLOW_STEP_MS = 6_000L
        const val MAX_REOPEN = 2
    }
}
