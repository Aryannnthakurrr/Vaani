package com.saathi.app.features.tasks

import android.content.pm.PackageManager
import com.saathi.app.core.AppContainer
import com.saathi.app.core.agent.AccessibilityActionExecutor
import com.saathi.app.core.agent.ActionPerformer
import com.saathi.app.core.agent.AgentLoop
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.agent.LiveScreenReader
import com.saathi.app.core.agent.ReplayPerformer
import com.saathi.app.core.agent.ReplayScreenReader
import com.saathi.app.core.agent.SafetyPolicy
import com.saathi.app.core.agent.ScreenSource
import com.saathi.app.core.agent.TaskContext
import com.saathi.app.core.agent.TreeSerializer
import com.saathi.app.core.agent.Verdict
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.Slots
import com.saathi.app.core.voice.tr
import com.saathi.app.features.inbox.LocalClassifier
import com.saathi.app.features.inbox.ReminderPlanner
import com.saathi.app.util.Packages
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Wires a [TaskSpec] to the agent loop on the live screen, or on recorded fixtures in replay mode. */
class TaskRunner(private val c: AppContainer) {
    private companion object {
        const val FLIPKART_SCENARIO = "flipkart_add_cart"
    }


    private val style get() = c.settings.current.replyStyle
    private val places by lazy { AndroidPlaceFinder(c.context) }

    /** Live eyes and hands, or the debug replay of the task's scenario. */
    fun io(scenario: String?): Pair<ScreenSource, ActionPerformer>? {
        if (c.settings.current.replayMode && scenario != null) {
            val replay = ReplayScreenReader.fromAssets(c.context.assets, scenario) ?: return null
            return replay to ReplayPerformer()
        }
        AgentService.instance ?: return null
        val reader = LiveScreenReader { AgentService.instance }
        return reader to AccessibilityActionExecutor(c.context, { AgentService.instance }, reader)
    }

    fun loop(screen: ScreenSource, performer: ActionPerformer, ui: AgentUi) = AgentLoop(
        backend = { c.backend }, screen = screen, performer = performer, ui = ui,
        style = { c.settings.current.replyStyle }, log = { c.db.actionLog().insert(it) },
    )

    /** @param preferredPkg an app the user named ("on Flipkart"); used when it can do the task. */
    suspend fun run(spec: TaskSpec, slots: Slots, ui: AgentUi, preferredPkg: String? = null): AgentOutcome {
        val pkg = targetPackage(spec, preferredPkg)
        val scenario = if (pkg == Packages.FLIPKART) FLIPKART_SCENARIO else spec.scenario
        val (screen, performer) = io(scenario) ?: return notReady(ui)
        val replay = c.settings.current.replayMode
        if (!replay && !installed(pkg)) {
            val msg = tr(style, "${Packages.displayName(pkg)} is not installed on this phone.",
                "${Packages.displayName(pkg)} इस फ़ोन में नहीं है।", "${Packages.displayName(pkg)} is phone mein nahi hai.")
            ui.say(msg)
            return AgentOutcome.Failed(msg)
        }
        // Replay runs on recorded screens, so it never looks up places or opens links.
        val link = if (replay) null else spec.deeplink?.invoke(slots, places)
        val task = TaskContext(taskHint = scenario, trustedDeeplinks = setOfNotNull(link))
        if (!replay) {
            // The first hop is Saathi's own code, still checked by the same policy as model actions.
            val launch = link?.let { AgentAction(AgentAction.OPEN_DEEPLINK, uri = it, reason = "task launch") }
                ?: AgentAction(AgentAction.OPEN_APP, pkg = pkg, reason = "task launch")
            val first = screen.read()
            if (SafetyPolicy.validate(launch, first, AgentMode.ACT, trustedDeeplinks = task.trustedDeeplinks) is Verdict.Allow) {
                performer.perform(launch, first)
                delay(1500)
                performer.awaitSettle()
            }
        }
        val goal = spec.goal(slots) + "\nTask notes: " + spec.addendum
        val outcome = loop(screen, performer, ui).run(goal, AgentMode.ACT, task)
        if (outcome is AgentOutcome.Done && spec.id == Tasks.PRACTO.id) afterPracto(outcome, ui)
        return outcome
    }

    /** Shopping uses Amazon, or Flipkart when the user asked for it or only Flipkart is installed. */
    private fun targetPackage(spec: TaskSpec, preferredPkg: String?): String {
        if (spec.id != Tasks.SHOP.id) return spec.pkg
        val replay = c.settings.current.replayMode
        if (preferredPkg == Packages.FLIPKART && (replay || installed(Packages.FLIPKART))) return Packages.FLIPKART
        return if (!replay && !installed(Packages.AMAZON) && installed(Packages.FLIPKART)) Packages.FLIPKART else spec.pkg
    }

    private fun installed(pkg: String) = try {
        c.context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun notReady(ui: AgentUi): AgentOutcome {
        val msg = tr(style, "Please turn on Saathi in Accessibility settings first. Open Saathi and follow the setup.",
            "पहले एक्सेसिबिलिटी सेटिंग में साथी चालू कीजिए। साथी खोलकर सेटअप पूरा कीजिए।",
            "Pehle Accessibility settings mein Saathi on kijiye. Saathi kholkar setup poora kijiye.")
        ui.say(msg)
        return AgentOutcome.Failed(msg)
    }

    /** Practo: extract the confirmation, write it to the calendar with −1 day / −2 h reminders. */
    private suspend fun afterPracto(done: AgentOutcome.Done, ui: AgentUi) {
        val screenText = done.lastScreen?.let(TreeSerializer::serialize) ?: return
        val appt = runCatching { c.backend.extractAppointment(screenText) }.getOrElse { LocalClassifier.extractAppointment(screenText) }
        if (appt.startIso == null) return
        c.settings.setLastAppointment(com.saathi.app.util.SaathiJson.encodeToString(com.saathi.app.core.net.Appointment.serializer(), appt))
        val eventId = c.calendar.insert(appt)
        ReminderPlanner.forAppointment(appt, ZonedDateTime.now()).forEach { c.reminders.add(c.db.reminders(), it) }
        if (eventId != null) ui.say(tr(style, "I also added it to your calendar with reminders.", "मैंने इसे कैलेंडर में रिमाइंडर के साथ जोड़ दिया है।",
            "Maine ise calendar mein reminder ke saath add kar diya hai."))
    }
}
