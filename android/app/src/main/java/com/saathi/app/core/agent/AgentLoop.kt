package com.saathi.app.core.agent

import com.saathi.app.core.data.ActionLogEntity
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.AgentStepRequest
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.HistoryItem
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.util.Packages
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** What a task adds to the loop: which scripted scenario/app it is and which deep links it built itself. */
data class TaskContext(
    val taskHint: String? = null,
    val trustedDeeplinks: Set<String> = emptySet(),
    val allowedPackages: Set<String> = Packages.ACT_ALLOWLIST,
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

    suspend fun run(goal: String, mode: AgentMode, task: TaskContext = TaskContext()): AgentOutcome {
        ui.setRunning(true)
        try {
            return loop(goal, mode, task)
        } catch (e: CancellationException) {
            logLine(goal, AgentAction("stop", reason = "user pressed stop"), "-", "Stopped by you", "cancelled")
            throw e
        } finally {
            ui.clearHighlight()
            ui.setRunning(false)
        }
    }

    private suspend fun loop(goal: String, mode: AgentMode, task: TaskContext): AgentOutcome {
        val seen = HashMap<String, Int>()
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
                    val msg = s("I can't work inside ${Packages.displayName(snap.pkg)}. I have stopped.",
                        "मैं ${Packages.displayName(snap.pkg)} के अंदर काम नहीं कर सकता। मैंने रोक दिया है।",
                        "Main ${Packages.displayName(snap.pkg)} ke andar kaam nahi kar sakta. Maine rok diya hai.")
                    return fail(goal, snap, msg)
                }
                null -> Unit
            }

            val fp = TreeSerializer.fingerprint(snap)
            val repeats = (seen[fp] ?: 0) + 1
            seen[fp] = repeats
            if (repeats > MAX_SAME_SCREEN) {
                return fail(goal, snap, s("I'm stuck on this screen. Shall I show you how to do it instead?",
                    "मैं इस स्क्रीन पर अटक गया हूँ। क्या मैं आपको तरीका दिखाऊँ?",
                    "Main is screen par atak gaya hoon. Kya main aapko tareeka dikhaun?"))
            }

            val action = nextAction(goal, mode, snap, task)
            if (!ActionParser.isComplete(action) || action.action !in AgentAction.ALL) {
                if (++invalidInARow >= 2) return fail(goal, snap, s("I got confused. Shall I show you instead?",
                    "मैं उलझ गया। क्या मैं आपको दिखाऊँ?", "Main confuse ho gaya. Kya main aapko dikhaun?"))
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
                logLine(goal, action, snap.pkg, "Finished: $msg", "done")
                ui.say(msg)
                return AgentOutcome.Done(msg, snap)
            }
            AgentAction.FAIL -> return fail(goal, snap, action.message.orEmpty())
            AgentAction.ASK_USER -> {
                val answer = ui.ask(action.question.orEmpty()) ?: return cancelled(goal, snap)
                remember(action, "user answered: $answer")
                logLine(goal, action, snap.pkg, "Asked you: ${action.question}", "ok")
                return null
            }
            AgentAction.WAIT -> {
                delay((action.ms ?: 1000).coerceIn(100, 5000))
                remember(action)
                return null
            }
            AgentAction.HIGHLIGHT -> {
                val node = snap.node(action.id)
                val instruction = action.instruction.orEmpty()
                ui.showHighlight(node?.bounds, instruction, action.gesture)
                ui.say(instruction)
                logLine(goal, action, snap.pkg, "Showed you: $instruction", "ok")
                if (!performer.awaitUserInteraction(GUIDE_WAIT_MS)) {
                    ui.say(s("Take your time. ", "आराम से कीजिए। ", "Aaram se kijiye. ") + instruction)
                    performer.awaitUserInteraction(GUIDE_WAIT_MS)
                }
                ui.clearHighlight()
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
        logLine(goal, original, snap.pkg, "Asked you: $summary — you said ${if (yes) "yes" else "no"}", if (yes) "ok" else "cancelled")
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

    private suspend fun nextAction(goal: String, mode: AgentMode, snap: ScreenSnapshot, task: TaskContext): AgentAction =
        try {
            backend().agentStep(AgentStepRequest(
                goal = goal, mode = mode.name, language = style().wire,
                screenText = TreeSerializer.serialize(snap), pkg = snap.pkg,
                screenshotB64 = snap.screenshotB64, history = history.takeLast(HISTORY), taskHint = task.taskHint,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentAction.fail(s("I can't reach my helper right now. Shall I try again later?",
                "अभी मैं अपने सर्वर तक नहीं पहुँच पा रहा। क्या बाद में फिर कोशिश करें?",
                "Abhi main server tak nahi pahunch pa raha. Kya baad mein try karein?"), "network: ${e.message}")
        }

    private fun remember(action: AgentAction, reason: String? = action.reason) {
        history += HistoryItem(action, reason)
    }

    private suspend fun blocked(goal: String, snap: ScreenSnapshot, action: AgentAction, why: String): AgentOutcome? {
        logLine(goal, action, snap.pkg, "Blocked for safety: $why", "blocked")
        remember(action, "BLOCKED by safety policy: $why")
        return null
    }

    private suspend fun fail(goal: String, snap: ScreenSnapshot?, message: String): AgentOutcome {
        logLine(goal, AgentAction.fail(message), snap?.pkg ?: "-", "Stopped: $message", "failed")
        ui.say(message)
        return AgentOutcome.Failed(message)
    }

    private suspend fun cancelled(goal: String, snap: ScreenSnapshot): AgentOutcome {
        logLine(goal, AgentAction("stop"), snap.pkg, "Stopped by you", "cancelled")
        return AgentOutcome.Cancelled
    }

    /** Stores the action and reason — never the raw screen text (SPEC §6.3). */
    private suspend fun logLine(goal: String, action: AgentAction, pkg: String, plain: String, outcome: String) {
        runCatching {
            log(ActionLogEntity(ts = clock(), goal = goal, actionJson = SaathiJson.encodeToString(AgentAction.serializer(), action.copy(text = action.text?.let { "…" })),
                reason = action.reason, pkg = pkg, plain = plain, outcome = outcome))
        }
    }

    private fun describe(a: AgentAction, snap: ScreenSnapshot): String {
        val app = Packages.displayName(snap.pkg)
        val target = snap.node(a.id)?.label?.take(40)
        return when (a.action) {
            AgentAction.TAP -> "Tapped \"${target ?: "an item"}\" in $app"
            AgentAction.TAP_XY -> "Tapped the screen in $app"
            AgentAction.TYPE -> "Typed \"${a.text}\" in $app"
            AgentAction.SCROLL -> "Scrolled ${a.direction} in $app"
            AgentAction.BACK -> "Went back"
            AgentAction.HOME -> "Went to the home screen"
            AgentAction.OPEN_APP -> "Opened ${Packages.displayName(a.pkg.orEmpty())}"
            AgentAction.OPEN_DEEPLINK -> "Opened $app with the trip details"
            else -> a.action
        }
    }

    private fun progressLine(a: AgentAction): String = when (a.action) {
        AgentAction.TYPE -> s("Searching for ${a.text}…", "${a.text} ढूँढ रहा हूँ…", "${a.text} search kar raha hoon…")
        else -> s("Still working on it…", "काम चल रहा है…", "Kaam chal raha hai…")
    }

    private fun s(en: String, hi: String, hinglish: String) = tr(style(), en, hi, hinglish)

    companion object {
        const val MAX_STEPS = 25
        const val MAX_SAME_SCREEN = 3
        const val HISTORY = 6
        const val GUIDE_WAIT_MS = 30_000L
    }
}
