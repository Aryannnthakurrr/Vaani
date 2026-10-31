package com.saathi.app

import com.saathi.app.core.agent.SafetyPolicy
import com.saathi.app.core.agent.ScreenNode
import com.saathi.app.core.agent.ScreenSnapshot
import com.saathi.app.core.agent.ScreenStop
import com.saathi.app.core.agent.Verdict
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.DeviceAuth
import com.saathi.app.core.net.LinkPrivacy
import com.saathi.app.core.privacy.PrivateSpans
import com.saathi.app.core.privacy.Redactor
import com.saathi.app.features.inbox.InboxPipeline
import com.saathi.app.util.Packages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Privacy guarantees (docs/PRIVACY_PITCH.md §8, docs/PRIVACY_AUDIT.md). */
class PrivacyTest {
    @Test
    fun linksLoseTheirPersonalPartsLikeOnTheServer() {
        val cases = Json.parseToJsonElement(File(Fixtures.root, "link_privacy/cases.json").readText()).jsonArray
        for (case in cases) {
            val c = case as JsonObject
            assertEquals(c["in"]!!.jsonPrimitive.content, c["out"]!!.jsonPrimitive.content, LinkPrivacy.strip(c["in"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun installKeysAreLongAndRandom() {
        val a = DeviceAuth.newKey()
        val b = DeviceAuth.newKey()
        assertEquals(43, a.length) // 32 bytes, base64url without padding
        assertNotEquals(a, b)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun emailBodiesReachTheCloudOnlyAsTokens() {
        val email = "Dear customer, your account no. 50100234567812 has been debited by Rs. 25,000.00 on 01-11-2026. " +
            "Card XXXX1234. If not you, call us."
        val extracted = PrivateSpans.extract(email)
        val tokenized = PrivateSpans.bind(extracted.text, extracted.spans.indices.map { it + 1L })
        val cloud = InboxPipeline.prepareTokenized("g1", "alerts@bank.example", tokenized, isOtp = false, fromContact = false).cloudItem!!
        assertFalse(cloud.text, "50100234567812" in cloud.text || "4567812" in cloud.text)
        assertFalse(cloud.text, "25,000" in cloud.text)
        assertTrue(cloud.text, "⟦P" in cloud.text)
    }

    @Test
    fun appointmentScreenTextIsRedactedBeforeItLeaves() {
        val screen = "Booking confirmed. Paid from a/c 912345678901 via UPI ramesh.k@okaxis"
        val out = Redactor.redact(screen).text
        assertFalse(out, "912345678901" in out || "ramesh.k@okaxis" in out)
    }

    private fun node(text: String) = ScreenNode("TextView", text, clickable = true, bounds = listOf(0, 0, 10, 10))
    private fun settings(title: String, vararg texts: String) = ScreenSnapshot(Packages.SETTINGS, title, false, texts.map { node(it) })

    @Test
    fun settingsAllowsOnlyTextSizeVolumeAndBrightness() {
        assertEquals(null, SafetyPolicy.preCheck(settings("Display", "Font size and style", "Dark mode"), AgentMode.ACT))
        assertEquals(null, SafetyPolicy.preCheck(settings("Sounds and vibration", "Volume"), AgentMode.ACT))
        assertEquals(ScreenStop.SETTINGS_RESTRICTED, SafetyPolicy.preCheck(settings("Security and privacy", "Screen lock type"), AgentMode.ACT))
        assertEquals(ScreenStop.SETTINGS_RESTRICTED, SafetyPolicy.preCheck(settings("Developer options", "USB debugging"), AgentMode.ACT))
        assertEquals(ScreenStop.SETTINGS_RESTRICTED, SafetyPolicy.preCheck(settings("सुरक्षा", "स्क्रीन लॉक"), AgentMode.ACT))
        assertEquals(ScreenStop.SETTINGS_RESTRICTED, SafetyPolicy.preCheck(settings("Accounts and backup"), AgentMode.ACT))
        // The Settings home list: Display may be tapped, Security and Apps may not.
        val home = settings("Settings", "Display", "Security and privacy", "Apps")
        assertEquals(null, SafetyPolicy.preCheck(home, AgentMode.ACT))
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 1), home, AgentMode.ACT))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 2), home, AgentMode.ACT) is Verdict.Block)
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 3), home, AgentMode.ACT) is Verdict.Block)
    }
}

