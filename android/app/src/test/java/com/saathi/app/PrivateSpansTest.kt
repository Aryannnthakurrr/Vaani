package com.saathi.app

import com.saathi.app.core.privacy.PrivateKind
import com.saathi.app.core.privacy.PrivateSpan
import com.saathi.app.core.privacy.PrivateSpans
import com.saathi.app.core.privacy.PrivateSpans.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpansTest {
    @Test
    fun otpAndBankValuesBecomeSlots() {
        val e = PrivateSpans.extract("482193 is your OTP for HDFC. Do not share.")
        assertEquals("⟦#0⟧ is your OTP for HDFC. Do not share.", e.text)
        assertEquals(listOf(PrivateSpan(PrivateKind.OTP, "482193")), e.spans)

        val d = PrivateSpans.extract("Rs.1,250.00 debited from A/c XX4821 on 31-Oct. Avl bal Rs 10,000")
        assertFalse(d.text.contains("1,250") || d.text.contains("4821") || d.text.contains("10,000"))
        assertEquals(listOf(PrivateKind.AMOUNT, PrivateKind.ACCOUNT, PrivateKind.AMOUNT), d.spans.map { it.kind })
        assertEquals("1250.00", d.spans[0].value)
        assertEquals("⟦#0⟧ debited from A/c ⟦#1⟧ on 31-Oct. Avl bal ⟦#2⟧", d.text)
    }

    @Test
    fun idsCardsAndOrdinaryText() {
        val e = PrivateSpans.extract("Aadhaar 2345 6789 0123 linked to PAN ABCPE1234F, card 4111 1111 1111 1111")
        assertEquals(setOf(PrivateKind.AADHAAR, PrivateKind.PAN, PrivateKind.CARD), e.spans.map { it.kind }.toSet())
        assertFalse(e.text.replace(Regex("⟦#\\d+⟧"), "").any(Char::isDigit)) // no digit leaks outside the slots
        // A bill reminder keeps its amount: only debits, credits and balances are private.
        val bill = PrivateSpans.extract("Your electricity bill of Rs 840 is due on 5 Nov")
        assertTrue(bill.spans.isEmpty())
        assertEquals("Your electricity bill of Rs 840 is due on 5 Nov", bill.text)
    }

    @Test
    fun cloudCopyNeverHoldsTheValues() {
        val sms = "Rs.1,250.00 debited from A/c XX4821 to SWIGGY. Avl bal Rs 10,000"
        val e = PrivateSpans.extract(sms)
        val tokenized = PrivateSpans.bind(e.text, e.spans.indices.map { 1000L + it }) // 4-digit ids look like codes
        val p = com.saathi.app.features.inbox.InboxPipeline.prepareTokenized("1", "AX-HDFCBK", tokenized, isOtp = false, fromContact = false)
        val cloud = p.cloudItem!!.text
        listOf("1,250", "1250", "4821", "10,000").forEach { assertFalse(it, cloud.contains(it)) }
        assertTrue(cloud.contains("⟦P1000⟧") && cloud.contains("SWIGGY")) // the token survives the second mask
        val otp = com.saathi.app.features.inbox.InboxPipeline.prepareTokenized("2", "AX-HDFCBK", "⟦P7⟧ is your OTP", isOtp = true, fromContact = false)
        assertEquals(null, otp.cloudItem) // OTP messages still never leave the phone
    }

    @Test
    fun bindPartsAndSpeech() {
        val bound = PrivateSpans.bind("Your OTP is ⟦#0⟧ and ⟦#1⟧ was debited", listOf(17L, 18L))
        assertEquals("Your OTP is ⟦P17⟧ and ⟦P18⟧ was debited", bound)
        assertEquals(
            listOf(Part.Public("Your OTP is "), Part.Private(17), Part.Public(" and "), Part.Private(18), Part.Public(" was debited")),
            PrivateSpans.parts(bound),
        )
        assertEquals("4 8 2 1 9 3", PrivateSpans.spoken(PrivateSpan(PrivateKind.OTP, "482193"), hindi = false))
        assertEquals("1250 rupees", PrivateSpans.spoken(PrivateSpan(PrivateKind.AMOUNT, "1250.00"), hindi = false))
        assertEquals("1250 रुपये", PrivateSpans.spoken(PrivateSpan(PrivateKind.AMOUNT, "1250.00"), hindi = true))
    }
}
