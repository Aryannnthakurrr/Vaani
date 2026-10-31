package com.saathi.app

import com.saathi.app.core.agent.SafetyPolicy
import com.saathi.app.core.agent.ScreenNode
import com.saathi.app.core.agent.ScreenSnapshot
import com.saathi.app.core.agent.ScreenStop
import com.saathi.app.core.agent.Verdict
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.util.Packages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private fun button(text: String) = ScreenNode("Button", text, clickable = true, bounds = listOf(0, 0, 100, 100))
    private fun screen(vararg nodes: ScreenNode, pkg: String = Packages.UBER) = ScreenSnapshot(pkg, "t", false, nodes.toList())

    @Test
    fun confirmWordsMatchEnglishAndHindi() {
        listOf("Book now", "Confirm Uber Go", "Pay ₹184", "Place order", "Buy Now", "Proceed to pay", "Send", "Request Uber",
            "Schedule", "Add to Cart", "बुक करें", "पुष्टि करें", "भुगतान करें", "ऑर्डर करें", "भेजें", "Confirm booking")
            .forEach { assertTrue(it, SafetyPolicy.CONFIRM_WORDS.containsMatchIn(it)) }
        listOf("Where to?", "Search Amazon.in", "Display", "Next", "Bookmarks", "Sender info", "Payments history", "Cancel ride")
            .forEach { assertFalse(it, SafetyPolicy.CONFIRM_WORDS.containsMatchIn(it)) }
    }

    @Test
    fun directTapOnCommitButtonNeedsConfirm() {
        val s = screen(button("Where to?"), button("Confirm Uber Go"))
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 1), s, AgentMode.ACT))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 2), s, AgentMode.ACT) is Verdict.NeedsConfirm)
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 2), s, AgentMode.ACT, confirmed = true))
        val wrapped = AgentAction(AgentAction.CONFIRM, summary = "Book it?", then = AgentAction(AgentAction.TAP, id = 2))
        assertEquals(Verdict.Allow, SafetyPolicy.validate(wrapped, s, AgentMode.ACT))
    }

    @Test
    fun contentDescriptionAlsoCounts() {
        val send = ScreenNode("ImageButton", desc = "Send", clickable = true, bounds = listOf(0, 0, 10, 10))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP, id = 1), screen(send), AgentMode.ACT) is Verdict.NeedsConfirm)
    }

    @Test
    fun pinOtpCvvAndSecureScreensStopTheAgent() {
        assertEquals(ScreenStop.SECRET_ENTRY, SafetyPolicy.preCheck(Fixtures.safety("upi_pin"), AgentMode.ACT, setOf(Packages.UBER)))
        assertEquals(ScreenStop.SECRET_ENTRY, SafetyPolicy.preCheck(Fixtures.safety("secure_window"), AgentMode.GUIDE))
        val otp = screen(ScreenNode("EditText", hint = "Enter OTP", editable = true))
        assertEquals(ScreenStop.SECRET_ENTRY, SafetyPolicy.preCheck(otp, AgentMode.ACT))
        val cvv = screen(ScreenNode("TextView", "CVV"), ScreenNode("EditText", editable = true))
        assertEquals(ScreenStop.SECRET_ENTRY, SafetyPolicy.preCheck(cvv, AgentMode.ACT))
        // A message *mentioning* OTP with no input field is not a PIN screen.
        val info = screen(ScreenNode("TextView", "We sent you an OTP"))
        assertNull(SafetyPolicy.preCheck(info, AgentMode.ACT))
    }

    @Test
    fun loginScreensAreForTheUser() {
        val login = Fixtures.safety("login_password")
        assertEquals(ScreenStop.LOGIN, SafetyPolicy.preCheck(login, AgentMode.ACT))
        val pwdId = login.nodes.indexOfFirst { it.hint == "Password" } + 1
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TYPE, id = pwdId, text = "x"), login, AgentMode.ACT) is Verdict.Block)
    }

    @Test
    fun allowlistGatesOpenAppAndForeignScreens() {
        val s = screen(button("x"))
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.OPEN_APP, pkg = Packages.PRACTO), s, AgentMode.ACT))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.OPEN_APP, pkg = "com.anydesk.anydeskandroid"), s, AgentMode.ACT) is Verdict.Block)
        assertEquals(ScreenStop.FOREIGN_APP, SafetyPolicy.preCheck(screen(button("x"), pkg = "com.phonepe.app"), AgentMode.ACT))
        assertNull(SafetyPolicy.preCheck(screen(button("x"), pkg = "com.phonepe.app"), AgentMode.GUIDE))
    }

    @Test
    fun onlySaathiBuiltDeepLinksOpen() {
        val s = screen(button("x"))
        val link = "uber://?action=setPickup"
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.OPEN_DEEPLINK, uri = link), s, AgentMode.ACT, trustedDeeplinks = setOf(link)))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.OPEN_DEEPLINK, uri = "https://evil.example/kyc"), s, AgentMode.ACT) is Verdict.Block)
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.OPEN_DEEPLINK, uri = "tel:*401*99"), s, AgentMode.ACT, trustedDeeplinks = setOf("tel:*401*99")) is Verdict.Block)
    }

    @Test
    fun neverTypesDialCodes() {
        val s = screen(ScreenNode("EditText", hint = "Search", editable = true))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TYPE, id = 1, text = "*401*9876543210#"), s, AgentMode.ACT) is Verdict.Block)
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.TYPE, id = 1, text = "Apollo Hospital"), s, AgentMode.ACT))
    }

    @Test
    fun guideModeOnlyHighlightsAsksAndFinishes() {
        val s = screen(button("Display"))
        for (a in listOf(AgentAction.TAP, AgentAction.TYPE, AgentAction.OPEN_APP, AgentAction.SCROLL, AgentAction.BACK)) {
            val v = SafetyPolicy.validate(AgentAction(a, id = 1, text = "x", pkg = Packages.SETTINGS, direction = "down"), s, AgentMode.GUIDE)
            assertTrue(a, v is Verdict.Block)
        }
        assertEquals(Verdict.Allow, SafetyPolicy.validate(AgentAction(AgentAction.HIGHLIGHT, id = 1, instruction = "Tap Display"), s, AgentMode.GUIDE))
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.HIGHLIGHT, id = 1, instruction = "x"), s, AgentMode.ACT) is Verdict.Block)
    }

    @Test
    fun tapXyOnlyWithScreenshotAndStillGated() {
        val node = ScreenNode("Button", "Pay", clickable = true, bounds = listOf(0, 0, 100, 100))
        val noShot = screen(node)
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP_XY, x = 50, y = 50), noShot, AgentMode.ACT) is Verdict.Block)
        val shot = noShot.copy(screenshotB64 = "abc")
        assertTrue(SafetyPolicy.validate(AgentAction(AgentAction.TAP_XY, x = 50, y = 50), shot, AgentMode.ACT) is Verdict.NeedsConfirm)
    }
}
