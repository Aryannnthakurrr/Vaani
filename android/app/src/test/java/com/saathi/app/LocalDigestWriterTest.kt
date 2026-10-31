package com.saathi.app

import com.saathi.app.core.net.DigestItem
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.features.inbox.LocalDigestWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalDigestWriterTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val items = listOf(
        DigestItem("bill_due", "BSES", 1240.0, "2026-11-05", "Bill · BSES"),
        DigestItem("subscription_renewal", "Netflix", 199.0, "2026-11-01", "Renewal · Netflix"),
        DigestItem("promo", "Myntra", null, null, "50% off shoes"),
        DigestItem("scam_suspected", null, null, null, "Suspicious message"),
    )

    @Test fun englishDigest() {
        val text = LocalDigestWriter.write(items, ReplyStyle.EN, "Kamla", today)
        assertTrue(text.startsWith("Good morning, Kamla."))
        assertTrue(text.contains("Your BSES bill of ₹1,240 is due on Thursday."))
        assertTrue(text.contains("Netflix renews tomorrow for ₹199."))
        assertTrue(text.contains("Warning: 1 message looks like a scam"))
        assertFalse(text.contains("Myntra"))
        assertTrue(text.indexOf("Netflix") < text.indexOf("BSES"))
    }

    @Test fun hindiDigest() {
        val text = LocalDigestWriter.write(items, ReplyStyle.HI, "कमला", today)
        assertTrue(text.startsWith("सुप्रभात कमला जी।"))
        assertTrue(text.contains("₹1,240"))
        assertTrue(text.contains("कल"))
        assertTrue(text.contains("सावधान"))
    }

    @Test fun hinglishDigest() {
        val text = LocalDigestWriter.write(items, ReplyStyle.HINGLISH, null, today)
        assertTrue(text.startsWith("Good morning."))
        assertTrue(text.contains("Netflix kal ₹199 mein renew hoga."))
    }

    @Test fun emptyDigest() {
        assertEquals("Good morning. Nothing new today. Have a nice day.",
            LocalDigestWriter.write(listOf(items[2]), ReplyStyle.EN, null, today))
    }
}
