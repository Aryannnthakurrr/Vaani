package com.saathi.app

import com.saathi.app.core.agent.ActionParser
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.Answer
import com.saathi.app.core.voice.LanguageStyle
import com.saathi.app.core.voice.LocalIntentRules
import com.saathi.app.core.voice.Vad
import com.saathi.app.core.voice.YesNo
import com.saathi.app.features.tasks.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRulesTest {

    @Test
    fun yesNoInThreeLanguages() {
        listOf("haan", "Haan ji", "yes please", "theek hai", "kar do", "book karo", "ok", "हाँ", "ठीक है").forEach {
            assertEquals(it, Answer.YES, YesNo.parse(it))
        }
        listOf("nahi", "no", "mat karo", "ruko", "rehne do", "cancel", "नहीं", "haan... nahi nahi", "ok mat karo").forEach {
            assertEquals(it, Answer.NO, YesNo.parse(it))
        }
        listOf("", "what is the price", "Apollo", null).forEach { assertEquals(Answer.UNCLEAR, YesNo.parse(it)) }
        assertTrue(YesNo.isDone("ho gaya"))
        assertTrue(YesNo.isDone("I'm done"))
        assertFalse(YesNo.isDone("wait"))
    }

    @Test
    fun languageStyleDetection() {
        assertEquals(ReplyStyle.EN, LanguageStyle.detect("book a cab to Apollo hospital"))
        assertEquals(ReplyStyle.HI, LanguageStyle.detect("कैब बुक करो"))
        assertEquals(ReplyStyle.HINGLISH, LanguageStyle.detect("Apollo jaane ke liye cab book kar do"))
        assertEquals(ReplyStyle.HINGLISH, LanguageStyle.detect("mujhe ye photo Aarav ko bhejni hai"))
        assertEquals(ReplyStyle.HI, LanguageStyle.detect("", "hi-IN"))
    }

    @Test
    fun phaseOneUtterancesRouteToCabInTheSameStyle() {
        val en = LocalIntentRules.route("book a cab")
        assertEquals("book_cab", en.intent)
        assertEquals("en", en.replyStyle)
        val hi = LocalIntentRules.route("कैब बुक करो")
        assertEquals("book_cab", hi.intent)
        assertEquals("hi", hi.replyStyle)
        assertTrue(hi.replyText!!.contains("कैब"))
        val mixed = LocalIntentRules.route("Apollo jaane ke liye cab book kar do")
        assertEquals("book_cab", mixed.intent)
        assertEquals("hinglish", mixed.replyStyle)
        assertEquals("Apollo", mixed.slots.destination)
        assertTrue(mixed.replyText!!.startsWith("Theek hai"))
    }

    @Test
    fun otherIntentsAndSlots() {
        assertEquals("Apollo Hospital", LocalIntentRules.route("Book a cab to Apollo Hospital please").slots.destination)
        assertEquals("guide", LocalIntentRules.route("Mujhe ye photo Aarav ko bhejni hai, kaise karun?").intent)
        assertEquals("book_doctor", LocalIntentRules.route("I need a heart doctor").intent)
        assertEquals("cardiologist", LocalIntentRules.route("I need a heart doctor").slots.specialty)
        assertEquals("shop_online", LocalIntentRules.route("order parle g biscuits").intent)
        assertEquals("camera", LocalIntentRules.route("check the expiry of this packet").intent)
        assertEquals("digest", LocalIntentRules.route("any updates today?").intent)
        assertEquals("book_house_help", LocalIntentRules.route("ghar ki safai ke liye bai chahiye").intent)
        assertEquals("unknown", LocalIntentRules.route("blue elephants").intent)
    }

    @Test
    fun vadEndsAfterSilenceAndCapsLength() {
        val loud = ShortArray(800) { if (it % 2 == 0) 8000 else -8000 }
        val quiet = ShortArray(800)
        val vad = Vad()
        repeat(10) { assertFalse(vad.feed(loud)) }
        var chunks = 0
        while (!vad.feed(quiet)) chunks++
        // 900 ms of silence at 50 ms per chunk ≈ 18 chunks.
        assertTrue("$chunks", chunks in 16..19)
        val nobody = Vad(noSpeechTimeoutMs = 1000)
        var n = 0
        while (!nobody.feed(quiet)) n++
        assertFalse(nobody.speechDetected)
        val talker = Vad(maxMs = 2000)
        var m = 0
        while (!talker.feed(loud)) m++
        assertTrue(m in 38..40)
    }

    @Test
    fun actionParserAcceptsValidAndRejectsBroken() {
        val tap = ActionParser.parse("```json\n{\"action\":\"tap\",\"id\":14,\"reason\":\"open search\"}\n```")
        assertEquals(AgentAction.TAP, tap.action)
        assertEquals(14, tap.id)
        val confirm = ActionParser.parse("""{"action":"confirm","summary":"Book it?","then":{"action":"tap","id":31},"reason":"r"}""")
        assertEquals(31, confirm.then!!.id)
        val pkg = ActionParser.parse("""{"action":"open_app","package":"com.ubercab"}""")
        assertEquals("com.ubercab", pkg.pkg)
        assertEquals(AgentAction.FAIL, ActionParser.parse("""{"action":"tap"}""").action)
        assertEquals(AgentAction.FAIL, ActionParser.parse("""{"action":"launch_rockets","id":1}""").action)
        assertEquals(AgentAction.FAIL, ActionParser.parse("no json here").action)
        assertEquals(AgentAction.FAIL, ActionParser.parse("""{"action":"confirm","summary":"x"}""").action)
        assertEquals(AgentAction.FAIL, ActionParser.parse("""{"action":"scroll","direction":"sideways"}""").action)
    }

    @Test
    fun uberDeepLinkIsEncoded() {
        val link = DeepLinks.uberString("Apollo Hospital, Sarita Vihar", 28.5402, 77.2833)
        assertTrue(link.startsWith("uber://?action=setPickup&pickup=my_location"))
        assertTrue(link.contains("dropoff%5Blatitude%5D=28.5402"))
        assertTrue(link.contains("dropoff%5Bnickname%5D=Apollo%20Hospital%2C%20Sarita%20Vihar"))
        assertNull(DeepLinks.uber("Apollo", null, null))
    }
}
