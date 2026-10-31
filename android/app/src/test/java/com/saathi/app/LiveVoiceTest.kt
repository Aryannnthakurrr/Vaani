package com.saathi.app

import com.saathi.app.core.voice.LiveEvent
import com.saathi.app.core.voice.LiveToolArgs
import com.saathi.app.core.voice.LiveWire
import com.saathi.app.core.voice.TranscriptLog
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveVoiceTest {
    private fun obj(raw: String): JsonObject = SaathiJson.parseToJsonElement(raw).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun encodesClientMessages() {
        assertEquals("config", obj(LiveWire.config("hinglish")).s("type"))
        assertEquals("hinglish", obj(LiveWire.config("hinglish")).s("reply_style"))

        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val audio = obj(LiveWire.audio(pcm, 4))
        assertEquals("audio", audio.s("type"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(audio.s("pcm_b64")))

        val text = obj(LiveWire.text("Apollo jaana hai"))
        assertEquals("text", text.s("type"))
        assertEquals("Apollo jaana hai", text.s("text"))

        val result = obj(LiveWire.toolResult("t1", JsonObject(mapOf("ok" to JsonPrimitive(true)))))
        assertEquals("tool_result", result.s("type"))
        assertEquals("t1", result.s("id"))
        assertEquals("true", result["result"]!!.jsonObject.s("ok"))

        assertEquals("end", obj(LiveWire.end()).s("type"))
    }

    @Test
    fun parsesServerMessages() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val audio = LiveWire.parse("""{"type":"audio","pcm_b64":"${Base64.getEncoder().encodeToString(pcm)}","sample_rate":24000}""")
        assertTrue(audio is LiveEvent.Audio)
        assertArrayEquals(pcm, (audio as LiveEvent.Audio).pcm)
        assertEquals(24_000, audio.sampleRate)

        val t = LiveWire.parse("""{"type":"transcript","role":"user","text":"Namaste","final":true}""") as LiveEvent.Transcript
        assertEquals("user", t.role)
        assertTrue(t.final)
        // Anything that is not "user" is Saathi's own speech.
        assertEquals("saathi", (LiveWire.parse("""{"type":"transcript","role":"model","text":"Hi"}""") as LiveEvent.Transcript).role)

        val tool = LiveWire.parse("""{"type":"tool","id":"c7","name":"start_task","args":{"task":"book_cab","destination":"Apollo"}}""") as LiveEvent.Tool
        assertEquals("c7", tool.id)
        assertEquals("start_task", tool.name)
        assertEquals("Apollo", tool.args.s("destination"))

        assertEquals(LiveEvent.Interrupted, LiveWire.parse("""{"type":"interrupted"}"""))
        assertEquals("quota", (LiveWire.parse("""{"type":"error","message":"quota"}""") as LiveEvent.Error).message)

        // Unknown, malformed or incomplete messages are ignored, never crash.
        assertNull(LiveWire.parse("""{"type":"turn_complete"}"""))
        assertNull(LiveWire.parse("not json"))
        assertNull(LiveWire.parse("""{"type":"tool","name":"start_task"}"""))
        assertNull(LiveWire.parse("""{"type":"audio","pcm_b64":"%%%"}"""))
    }

    @Test
    fun transcriptChunksFoldIntoLines() {
        val log = TranscriptLog()
        log.apply(LiveEvent.Transcript("user", "Mujhe Apollo", false))
        var lines = log.apply(LiveEvent.Transcript("user", " jaana hai", true))
        assertEquals(1, lines.size)
        assertEquals("Mujhe Apollo jaana hai", lines[0].text)
        assertTrue(lines[0].final)

        log.apply(LiveEvent.Transcript("saathi", "Theek", false))
        lines = log.apply(LiveEvent.Transcript("saathi", " hai, cab book karti hoon.", false))
        assertEquals(2, lines.size)
        assertEquals("Theek hai, cab book karti hoon.", lines[1].text)
        assertFalse(lines[1].final)

        lines = log.closeOpen()
        assertTrue(lines[1].final)
        // A new chunk after a closed line starts a new line.
        lines = log.apply(LiveEvent.Transcript("saathi", "Kuch aur?", true))
        assertEquals(3, lines.size)
    }

    @Test
    fun toolArgumentsMapToSlots() {
        val flat = obj("""{"task":"book_cab","destination":"Apollo Hospital"}""")
        assertEquals("book_cab", LiveToolArgs.task(flat))
        assertEquals("Apollo Hospital", LiveToolArgs.slots(flat).destination)

        val nested = obj("""{"task":"shop_online","slots":{"product":"Parle-G","quantity":2,"app":"Flipkart"}}""")
        val slots = LiveToolArgs.slots(nested)
        assertEquals("Parle-G", slots.product)
        assertEquals(2, slots.quantity)
        assertTrue(LiveToolArgs.prefersFlipkart(nested))
        assertFalse(LiveToolArgs.prefersFlipkart(flat))

        val doctor = LiveToolArgs.slots(obj("""{"task":"book_doctor","slots":{"specialty":"cardiologist","date":"tomorrow"}}"""))
        assertEquals("cardiologist", doctor.specialty)
        assertEquals("tomorrow", doctor.date)

        // Blank values count as missing so the task asks for them.
        assertNull(LiveToolArgs.slots(obj("""{"destination":"  "}""")).destination)

        assertEquals("digest", LiveToolArgs.screen(obj("""{"screen":"digest"}""")))
        assertNull(LiveToolArgs.screen(obj("""{"screen":"com.phonepe.app"}""")))
        assertEquals("send a photo to Aarav", LiveToolArgs.goal(obj("""{"goal":"send a photo to Aarav"}""")))
        assertEquals("http://sbi-kyc.in", LiveToolArgs.url(obj("""{"url":"http://sbi-kyc.in"}""")))
    }
}
