package com.saathi.app

import com.saathi.app.features.scam.MessageScanner
import com.saathi.app.features.scam.ScamLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageScannerTest {

    @Test fun forwardingCodeSmsIsRed() {
        val r = MessageScanner.scan("Courier on hold. Dial *401*9876543210 to reschedule", fromContact = false)
        assertEquals(ScamLevel.RED, r.level)
        assertEquals("scam_red_ussd", r.phraseKey)
        assertTrue("ussd_forwarding" in r.reasons)
    }

    @Test fun otpSmsIsNotAMessageScamByItself() {
        // The in-call OTP alert comes from CallGuard, not from the message scanner.
        val r = MessageScanner.scan("Your OTP for transaction is 482913. Do not share.", fromContact = false)
        assertEquals(ScamLevel.NONE, r.level)
        assertNull(r.phraseKey)
    }

    @Test fun apkLinkIsRed() {
        val r = MessageScanner.scan("Your SBI reward points expire today! Install: http://bit.ly/sbi-rewards.apk", false)
        assertEquals(ScamLevel.RED, r.level)
        assertEquals("scam_red_apk", r.phraseKey)
    }

    @Test fun kycPhishingLinkIsRed() {
        val r = MessageScanner.scan("Dear customer your SBI KYC will expire today. Update now at sbi-kyc-update.xyz", false)
        assertEquals(ScamLevel.RED, r.level)
        assertTrue("dangerous_link" in r.reasons)
        assertTrue("kyc" in r.reasons)
    }

    @Test fun electricityThreatWithoutLinkIsAmber() {
        val r = MessageScanner.scan("Dear consumer your electricity will be disconnected tonight. Call 9876543210 now", false)
        assertEquals(ScamLevel.AMBER, r.level)
        assertEquals("scam_amber_message", r.phraseKey)
    }

    @Test fun normalBillRaisesNothing() {
        val r = MessageScanner.scan("Your BSES electricity bill of Rs 1,240 is due on 05-11-2026. Pay at bsesdelhi.com", false)
        assertEquals(ScamLevel.NONE, r.level)
        assertTrue(r.urlsToCheck.isEmpty())
    }

    @Test fun contactMessagesOnlyGetInfo() {
        val r = MessageScanner.scan("Maa, KBC lottery mein prize jeeta kya?", fromContact = true)
        assertEquals(ScamLevel.INFO, r.level)
        assertNull(r.phraseKey)
    }

    @Test fun unknownLinksAreSentForChecking() {
        val r = MessageScanner.scan("See the photos at myholidaypics.net/album", false)
        assertEquals(listOf("myholidaypics.net/album"), r.urlsToCheck)
        assertEquals(ScamLevel.RED, MessageScanner.withVerdict(r, "dangerous").level)
        assertEquals(ScamLevel.AMBER, MessageScanner.withVerdict(r, "suspicious").level)
        assertEquals(ScamLevel.NONE, MessageScanner.withVerdict(r, "safe").level)
    }
}
