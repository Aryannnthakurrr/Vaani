package com.saathi.app

import com.saathi.app.features.scam.UrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlExtractorTest {

    @Test fun extractsFullUrlsBareDomainsAndShorteners() {
        val urls = UrlExtractor.extract("Update KYC at https://sbi-kyc.xyz/login, or visit bit.ly/3abc and www.amazon.in.")
        assertEquals(listOf("https://sbi-kyc.xyz/login", "bit.ly/3abc", "www.amazon.in"), urls)
    }

    @Test fun ignoresEmailsAmountsAndDates() {
        assertTrue(UrlExtractor.extract("Mail care@hdfcbank.com. Paid Rs.500 on 05.11.2026, Dr.Mehta").isEmpty())
    }

    @Test fun hostAndPath() {
        assertEquals("sbi-kyc.xyz", UrlExtractor.host("https://www.SBI-kyc.xyz:8080/a?b=1"))
        assertEquals("/a?b=1", UrlExtractor.path("https://sbi-kyc.xyz/a?b=1"))
    }

    @Test fun apkRule() {
        assertTrue(UrlExtractor.isApkLink("http://bit.ly/x/SBI_Rewards.apk"))
        assertTrue(UrlExtractor.isApkLink("https://get-rewards.in/download?id=7"))
        assertTrue(UrlExtractor.isApkLink("https://cdn.example.com/apk/v2"))
        assertTrue(UrlExtractor.isApkLink("example.com/app.APK"))
        assertFalse(UrlExtractor.isApkLink("https://www.amazon.in/orders"))
    }

    @Test fun shortenerDetection() {
        assertTrue(UrlExtractor.isShortener("https://bit.ly/3abc"))
        assertFalse(UrlExtractor.isShortener("https://amazon.in"))
    }
}
