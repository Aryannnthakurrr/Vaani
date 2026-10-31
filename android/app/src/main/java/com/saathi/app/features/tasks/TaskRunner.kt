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
import com.saathi.app.features.appointments.AppointmentState
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
        // Cabs go straight to the deep link, as before: no place-choice or ride-advice questions first.
        val cabSlots = if (spec.id == Tasks.UBER.id) slots.copy(destination = slots.destination?.let(DeepLinks::cleanDestination)) else slots
        val (taskSlots, lookup) = if (replay || spec.id == Tasks.UBER.id) cabSlots to places else resolvePlace(spec, slots, ui)
        val link = if (replay) null else spec.deeplink?.invoke(taskSlots, lookup)
        val task = TaskContext(taskHint = scenario, trustedDeeplinks = setOfNotNull(link), appPackage = pkg.takeUnless { replay })
        if (!replay) {
            // The first hop is Saathi's own code, still checked by the same policy as model actions.
            val launch = link?.let { AgentAction(AgentAction.OPEN_DEEPLINK, uri = it, reason = "task launch") }
                ?: AgentAction(AgentAction.OPEN_APP, pkg = pkg, reason = "task launch")
            val first = screen.read()
            if (SafetyPolicy.validate(launch, first, AgentMode.ACT, trustedDeeplinks = task.trustedDeeplinks) is Verdict.Allow) {
                performer.perform(launch, first)
                delay(1500)
                performer.awaitSettle()
                awaitLoaded(screen, pkg)
            }
        }
        val about = c.profiles.contextLine()?.let { "\nAbout the user (respect it): $it" }.orEmpty()
        val goal = spec.goal(taskSlots) + "\nTask notes: " + spec.addendum + about
        val outcome = loop(screen, performer, ui).run(goal, AgentMode.ACT, task)
        if (outcome is AgentOutcome.Done && spec.id == Tasks.PRACTO.id) afterPracto(outcome, ui)
        return outcome
    }

    /**
     * A destination with matches in different cities ("Apollo Hospital": Delhi or Faridabad) is asked about once,
     * then pinned. An unclear answer is asked once more; still unclear, Saathi says so and lets the agent search
     * in the app instead of guessing a place.
     */
    private suspend fun resolvePlace(spec: TaskSpec, slots: Slots, ui: AgentUi): Pair<Slots, PlaceLookup> {
        if (spec.deeplink == null) return slots to places
        val said = slots.destination?.takeIf { it.isNotBlank() } ?: return slots to places
        // "To my doctor's appointment" → the clinic of the next booked appointment.
        val dest = AppointmentState.destinationFor(said, lastAppointment(), ZonedDateTime.now()) ?: said
        if (dest != said) ui.say(tr(style, "Going to your appointment at $dest.", "आपके अपॉइंटमेंट पर: $dest।", "Aapke appointment par: $dest."))
        val resolved = slots.copy(destination = dest)
        val options = PlaceChoice.options(places.candidates(dest))
        if (options.isEmpty()) return resolved to places
        val question = PlaceChoice.question(dest, options, style)
        val chosen = PlaceChoice.match(ui.ask(question), options)
            ?: PlaceChoice.match(ui.ask(tr(style, "Sorry, which area? ", "माफ़ कीजिए, कौन सा इलाक़ा? ", "Sorry, kaun sa area? ") +
                options.mapNotNull { it.area }.joinToString(", ")), options)
        if (chosen == null) {
            ui.say(tr(style, "I'm not sure which one you mean, so I'll search for it in the app.",
                "मुझे पक्का नहीं पता आपका मतलब कौन सा है, इसलिए मैं ऐप में ढूँढती हूँ।",
                "Mujhe pakka nahi pata aapka matlab kaun sa hai, isliye main app mein dhoondhti hoon."))
            return resolved to PlaceLookup { null }
        }
        return slots.copy(destination = "$dest, ${chosen.area}") to PlaceLookup { chosen.at }
    }

    private fun lastAppointment() = c.settings.current.lastAppointment?.let {
        runCatching { com.saathi.app.util.SaathiJson.decodeFromString(com.saathi.app.core.net.Appointment.serializer(), it) }.getOrNull()
    }

    /**
     * A splash screen (Pronto's logo, a blank React Native view) has almost nothing to tap; asking the model about it
     * costs a whole step. Waits up to 5 s for the app's real first screen.
     */
    private suspend fun awaitLoaded(screen: ScreenSource, pkg: String) {
        repeat(10) {
            val snap = screen.read()
            if (snap.pkg == pkg && !snap.needsScreenshot && snap.interactiveCount >= 3) return
            delay(500)
        }
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
