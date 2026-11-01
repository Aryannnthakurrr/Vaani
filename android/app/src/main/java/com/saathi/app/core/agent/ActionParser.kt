package com.saathi.app.core.agent

import com.saathi.app.core.net.AgentAction
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.Json

/** Parses model output into an [AgentAction], tolerating code fences and stray prose around the JSON. */
object ActionParser {
    private val strict = Json(SaathiJson) { coerceInputValues = false }

    fun parse(raw: String): AgentAction {
        val json = extractObject(raw) ?: return AgentAction.fail("I got confused. Shall I show you instead?", "unparseable")
        val action = runCatching { strict.decodeFromString(AgentAction.serializer(), json) }.getOrNull()
            ?: return AgentAction.fail("I got confused. Shall I show you instead?", "invalid json")
        return if (action.action in AgentAction.ALL && isComplete(action)) action
        else AgentAction.fail("I got confused. Shall I show you instead?", "incomplete ${action.action}")
    }

    /** Required fields per action type (docs/engineering/CONTRACTS.md). */
    fun isComplete(a: AgentAction): Boolean = when (a.action) {
        AgentAction.TAP -> a.id != null
        AgentAction.TAP_XY -> a.x != null && a.y != null
        AgentAction.TYPE -> a.id != null && a.text != null
        AgentAction.SCROLL -> a.direction in setOf("up", "down", "left", "right")
        AgentAction.OPEN_APP -> !a.pkg.isNullOrBlank()
        AgentAction.OPEN_DEEPLINK -> !a.uri.isNullOrBlank()
        AgentAction.WAIT -> a.ms != null
        AgentAction.ASK_USER -> !a.question.isNullOrBlank()
        AgentAction.CONFIRM -> !a.summary.isNullOrBlank() && a.then != null && isComplete(a.then)
        AgentAction.HIGHLIGHT -> !a.instruction.isNullOrBlank()
        AgentAction.DONE, AgentAction.FAIL -> !a.message.isNullOrBlank()
        else -> true
    }

    private fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }
}
