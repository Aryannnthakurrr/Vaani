package com.saathi.app

import com.saathi.app.features.scam.LocalLinkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLinkRulesTest {

    private fun verdict(url: String) = LocalLinkRules.verdict(url).verdict

    @Test fun brandWithLureOnUnofficialDomainIsDangerous() {
        assertEquals("dangerous", verdict("http://sbi-kyc.xyz/login"))
    }

    @Test fun homoglyphLookalikesAreDangerous() {
        assertEquals("dangerous", verdict("https://onlinesbl.com"))
        assertEquals("dangerous", verdict("amaz0n.in/refund"))
        assertEquals("dangerous", verdict("https://flipkartt.com"))
        assertTrue(LocalLinkRules.signals("https://hdfcbamk.com").any { it.first.startsWith("lookalike") })
    }

    @Test fun punycodeIsFlagged() {
        val v = LocalLinkRules.verdict("http://xn--sbi-xyz.com")
        assertTrue(v.verdict != "safe")
        assertTrue("punycode" in v.signals)
    }

    @Test fun ipHostIsSuspicious() {
        val v = LocalLinkRules.verdict("http://192.168.10.5/login")
        assertEquals("suspicious", v.verdict)
        assertTrue("ip_host" in v.signals)
    }

    @Test fun apkIsDangerous() {
        assertEquals("dangerous", verdict("https://rewards-points.in/app.apk"))
    }

    @Test fun officialSitesAreSafe() {
        assertEquals("safe", verdict("https://www.amazon.in/deals"))
        assertEquals("safe", verdict("https://flipkart.com"))
        assertEquals("safe", verdict("https://www.onlinesbi.sbi/"))
        assertEquals("safe", verdict("https://axisbank.com/login"))
    }

    @Test fun ordinaryWordsAreNotLookalikes() {
        assertEquals("safe", verdict("https://party.com"))
        assertEquals("safe", verdict("https://example.org/news"))
    }

    @Test fun reasonsAreBilingual() {
        val v = LocalLinkRules.verdict("http://sbi-kyc.xyz")
        assertTrue(v.reasonSimple!!.contains("fake"))
        assertTrue(v.reasonSimpleHi!!.contains("नकली"))
    }
}
