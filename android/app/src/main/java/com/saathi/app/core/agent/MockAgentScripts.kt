package com.saathi.app.core.agent

import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentStepRequest
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScriptStep(
    @SerialName("when_contains") val whenContains: List<String> = emptyList(),
    val action: AgentAction,
)

@Serializable
data class AgentScript(
    val scenario: String,
    val mode: String,
    @SerialName("goal_keywords") val goalKeywords: List<String> = emptyList(),
    val steps: List<ScriptStep>,
)

/**
 * Offline replay of `fixtures/agent_scripts`, mirroring the backend mock selection rule in docs/CONTRACTS.md.
 * Used by [com.saathi.app.core.net.FakeBackendClient] and by JVM tests.
 */
class MockAgentScripts(private val scripts: List<AgentScript>) {

    fun pick(goal: String, taskHint: String?): AgentScript? {
        scripts.firstOrNull { it.scenario == taskHint }?.let { return it }
        val g = goal.lowercase()
        return scripts.firstOrNull { s -> s.goalKeywords.any { g.contains(it.lowercase()) } }
    }

    fun step(request: AgentStepRequest): AgentAction {
        val script = pick(request.goal, request.taskHint)
            ?: return AgentAction.fail("I don't know how to do that yet. Shall I show you instead?", "mock: no script")
        if (script.steps.isEmpty()) return AgentAction.fail("Nothing to do.", "mock: empty script")
        val start = request.history.size
        val match = script.steps.drop(start).firstOrNull { s -> s.whenContains.all { request.screenText.contains(it) } }
        return match?.action ?: script.steps[minOf(start, script.steps.lastIndex)].action
    }

    companion object {
        fun parse(jsonTexts: List<String>) = MockAgentScripts(jsonTexts.map { SaathiJson.decodeFromString(AgentScript.serializer(), it) })
    }
}
