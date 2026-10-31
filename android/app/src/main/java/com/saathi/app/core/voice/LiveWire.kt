package com.saathi.app.core.voice

import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** One line of the live conversation shown in big text on screen. */
data class TranscriptLine(val role: String, val text: String, val final: Boolean)

/** Server → app messages of `WS /live/voice` (docs/LEARNING.md §2). */
sealed interface LiveEvent {
    data class Audio(val pcm: ByteArray, val sampleRate: Int) : LiveEvent
    data class Transcript(val role: String, val text: String, val final: Boolean) : LiveEvent
    data class Tool(val id: String, val name: String, val args: JsonObject) : LiveEvent
    data object Interrupted : LiveEvent
    data class Error(val message: String) : LiveEvent
}

/**
 * Encoding and decoding for the realtime voice socket. Pure (java.util.Base64, no android.*), so it is unit-tested.
 */
object LiveWire {
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun config(replyStyle: String): String = obj("config", "reply_style" to JsonPrimitive(replyStyle))

    fun audio(pcm: ByteArray, length: Int = pcm.size): String =
        obj("audio", "pcm_b64" to JsonPrimitive(b64.encodeToString(if (length == pcm.size) pcm else pcm.copyOf(length))))

    fun text(text: String): String = obj("text", "text" to JsonPrimitive(text))

    fun toolResult(id: String, result: JsonElement): String = obj("tool_result", "id" to JsonPrimitive(id), "result" to result)

    fun end(): String = obj("end")

    /** Unknown or malformed messages decode to null and are ignored, so a newer server never crashes the app. */
    fun parse(raw: String): LiveEvent? {
        val o = runCatching { SaathiJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return when (str("type")) {
            "audio" -> str("pcm_b64")?.let { data ->
                runCatching { LiveEvent.Audio(b64d.decode(data), o["sample_rate"]?.jsonPrimitive?.intOrNull ?: 24_000) }.getOrNull()
            }
            "transcript" -> LiveEvent.Transcript(
                role = if (str("role") == "user") "user" else "saathi",
                text = str("text").orEmpty(),
                final = o["final"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "tool" -> {
                val id = str("id") ?: return null
                val name = str("name") ?: return null
                val args = runCatching { o["args"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                LiveEvent.Tool(id, name, args)
            }
            "interrupted" -> LiveEvent.Interrupted
            "error" -> LiveEvent.Error(str("message") ?: "error")
            else -> null
        }
    }

    private fun obj(type: String, vararg fields: Pair<String, JsonElement>): String = buildJsonObject {
        put("type", JsonPrimitive(type))
        fields.forEach { (k, v) -> put(k, v) }
    }.toString()
}

/**
 * Folds streamed transcript chunks into display lines: a non-final chunk extends the open line of the same role;
 * `final` closes it. Keeps the last [max] lines.
 */
class TranscriptLog(private val max: Int = 20) {
    private val lines = ArrayList<TranscriptLine>()

    fun apply(t: LiveEvent.Transcript): List<TranscriptLine> {
        val open = lines.lastOrNull()?.takeIf { it.role == t.role && !it.final }
        if (open != null) {
            lines[lines.lastIndex] = open.copy(text = join(open.text, t.text), final = t.final)
        } else if (t.text.isNotBlank()) {
            lines += TranscriptLine(t.role, t.text.trim(), t.final)
        }
        while (lines.size > max) lines.removeAt(0)
        return lines.toList()
    }

    /** A new turn by the other speaker closes whatever line was still open. */
    fun closeOpen(): List<TranscriptLine> {
        lines.lastOrNull()?.takeIf { !it.final }?.let { lines[lines.lastIndex] = it.copy(final = true) }
        return lines.toList()
    }

    private fun join(a: String, b: String): String = when {
        b.isEmpty() -> a
        a.isEmpty() -> b.trim()
        a.endsWith(" ") || b.startsWith(" ") -> (a + b).replace(Regex("\\s+"), " ").trim()
        else -> "$a$b"
    }
}
