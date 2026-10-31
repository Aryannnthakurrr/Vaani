package com.saathi.app.features.guide

import com.saathi.app.core.AppContainer
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.agent.TaskContext
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.TaskRunner

/**
 * "Show me how" (SPEC §7.2): the agent loop in GUIDE mode. The model may only highlight, ask, finish or
 * fail — [com.saathi.app.core.agent.SafetyPolicy] blocks every tap, type and app launch — so the user does
 * every tap themselves. Works in any app because it never acts.
 */
class GuideMode(private val c: AppContainer, private val runner: TaskRunner) {

    suspend fun run(goal: String, ui: AgentUi): AgentOutcome {
        val scenario = if (c.settings.current.replayMode) replayScenario(goal) else null
        val (screen, performer) = runner.io(scenario) ?: run {
            val style = c.settings.current.replyStyle
            val msg = tr(style, "Please turn on Saathi in Accessibility settings first.", "पहले एक्सेसिबिलिटी सेटिंग में साथी चालू कीजिए।",
                "Pehle Accessibility settings mein Saathi on kijiye.")
            ui.say(msg)
            return AgentOutcome.Failed(msg)
        }
        val guideGoal = goal + "\nGuide notes: one short instruction per step, name the button by colour, position and label. " +
            "If the user goes to the wrong screen, gently tell them how to get back. Praise them when finished."
        // Any app is allowed in GUIDE mode: the allowlist only gates acting, and guide mode never acts.
        return runner.loop(screen, performer, ui).run(guideGoal, AgentMode.GUIDE, TaskContext(taskHint = scenario, allowedPackages = emptySet()))
    }

    /** Picks the recorded GUIDE scenario that matches the user's words (demo/replay only). */
    private fun replayScenario(goal: String): String =
        c.mockScripts.pick(goal, null)?.takeIf { it.mode == "GUIDE" }?.scenario ?: "gallery_share"
}
