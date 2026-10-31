package com.saathi.app.core.voice

import com.saathi.app.core.AppContainer
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.net.DigestItem
import com.saathi.app.core.net.Slots
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure argument mapping for the Live tools, kept free of android.* so it is unit-tested. */
object LiveToolArgs {
    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }

    /** Slots may arrive flat (`{"task":..,"destination":..}`) or nested under `slots`. */
    fun slots(args: JsonObject): Slots {
        val s = (args["slots"] as? JsonObject) ?: args
        return Slots(
            destination = s.str("destination"), product = s.str("product"), specialty = s.str("specialty"),
            doctor = s.str("doctor"), area = s.str("area"), time = s.str("time"), date = s.str("date"),
            service = s.str("service"), quantity = s["quantity"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
        )
    }

    fun task(args: JsonObject): String? = args.str("task")

    fun goal(args: JsonObject): String? = args.str("goal")

    fun url(args: JsonObject): String? = args.str("url")

    /** Only Saathi's own screens can be opened from voice. */
    fun screen(args: JsonObject): String? = args.str("screen")?.takeIf { it in SCREENS }

    /** "Flipkart" named in any slot or the task args means the user prefers Flipkart for shopping. */
    fun prefersFlipkart(args: JsonObject): Boolean = args.toString().contains("flipkart", ignoreCase = true)

    val SCREENS = setOf("digest", "camera", "alerts", "settings", "clinic_call", "care", "log")

    fun ok(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(true))
        fields.forEach { (k, v) -> if (v != null) put(k, JsonPrimitive(v.toString())) }
    }

    fun error(message: String): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }
}

/**
 * Executes Gemini Live's tool calls on the phone (docs/LEARNING.md §2). Tasks and guides run through the normal
 * [com.saathi.app.core.Assistant] path with every safety gate; while they run the live session is paused, and when
 * they end the model is told the outcome so it can carry on the conversation.
 */
class LiveTools(
    private val c: AppContainer,
    private val session: () -> LiveVoiceSession?,
) : ToolHandler {

    override suspend fun handle(name: String, args: JsonObject): JsonElement = when (name) {
        "start_task" -> {
            val spec = LiveToolArgs.task(args)?.let(Tasks::forIntent)
            if (spec == null) LiveToolArgs.error("unknown task") else {
                val preferred = if (LiveToolArgs.prefersFlipkart(args)) com.saathi.app.util.Packages.FLIPKART else null
                runInBackground(c.assistant.startTask(spec, LiveToolArgs.slots(args), preferred))
                LiveToolArgs.ok("status" to "started", "note" to "The phone is doing it now; you will get a [task finished] note.")
            }
        }
        "start_guide" -> {
            val goal = LiveToolArgs.goal(args)
            if (goal == null) LiveToolArgs.error("missing goal") else {
                runInBackground(c.assistant.startGuide(goal))
                LiveToolArgs.ok("status" to "started")
            }
        }
        "open_screen" -> {
            val screen = LiveToolArgs.screen(args)
            if (screen == null) LiveToolArgs.error("unknown screen") else {
                c.assistant.openScreen(screen)
                LiveToolArgs.ok("opened" to screen)
            }
        }
        "read_updates" -> {
            val items = c.digest.items()
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("items", SaathiJson.encodeToJsonElement(ListSerializer(DigestItem.serializer()), items.take(MAX_ITEMS)))
            }
        }
        "check_link" -> {
            val url = LiveToolArgs.url(args)
            if (url == null) LiveToolArgs.error("missing url") else {
                val v = c.backend.checkLink(url)
                LiveToolArgs.ok("verdict" to v.verdict, "reason" to v.reasonSimple)
            }
        }
        else -> LiveToolArgs.error("unknown tool $name")
    }

    /** Pause the conversation while the task drives the phone, then report back and resume. */
    private fun runInBackground(job: Job) {
        session()?.pause()
        c.scope.launch {
            job.join()
            val note = when (val o = c.assistant.lastOutcome.value) {
                is AgentOutcome.Done -> "[task finished: ${o.message}]"
                is AgentOutcome.Failed -> "[task failed: ${o.message}]"
                AgentOutcome.Cancelled -> "[task cancelled by the user]"
                null -> "[task ended]"
            }
            session()?.let {
                it.resume()
                it.sendText(note)
            }
        }
    }

    private companion object {
        const val MAX_ITEMS = 15
    }
}
