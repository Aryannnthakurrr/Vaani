package com.saathi.app

import com.saathi.app.core.privacy.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test fun otpMessageIsHiddenEntirely() {
        val r = Redactor.redact("Your OTP for transaction is 482913. Do not share.")
        assertTrue(r.isOtp)
        assertEquals("482913", r.otpCode)
        assertEquals(Redactor.OTP_PLACEHOLDER, r.text)
    }

    @Test fun otpVariants() {
        assertEquals("1234", Redactor.detectOtp("1234 is your one time password for login"))
        assertEquals("998877", Redactor.detectOtp("Your verification code is 998877"))
        assertEquals("556677", Redactor.detectOtp("आपका ओटीपी 556677 है"))
        assertEquals("4521", Redactor.detectOtp("Use code is 4521 to log in"))
    }

    @Test fun billIsNotAnOtp() {
        val text = "Your BSES electricity bill of Rs 1,240 is due on 05-11-2026."
        assertNull(Redactor.detectOtp(text))
        val r = Redactor.redact(text)
        assertFalse(r.isOtp)
        assertEquals(text, r.text)
    }

    @Test fun amountsAndDatesSurvive() {
        val text = "Paid ₹12,499.50 on 31/10/2026 at 4:30 PM"
        assertEquals(text, Redactor.redact(text).text)
    }

    @Test fun luhnValidCardIsMasked() {
        val r = Redactor.redact("Card 4111 1111 1111 1111 was used for ₹500")
        assertEquals("Card XXXX-XXXX-XXXX-1111 was used for ₹500", r.text)
    }

    @Test fun nonLuhnLongNumberIsNotTreatedAsCard() {
        // Not a card (fails Luhn), but 11+ digits is no phone number either: masked as a long reference number.
        assertEquals("Ref XX0123", Redactor.redact("Ref 1234567890123").text)
        assertTrue(Redactor.redact("Call 9812345678").text.contains("9812345678")) // ordinary phone numbers stay
    }

    @Test fun aadhaarIsMasked() {
        assertEquals("Aadhaar XXXX XXXX 9012 linked", Redactor.redact("Aadhaar 1234 5678 9012 linked").text)
        assertEquals("UID XXXX XXXX 9012", Redactor.redact("UID 123456789012").text)
    }

    @Test fun accountNumberNearKeywordKeepsLastFour() {
        val r = Redactor.redact("Rs 500 debited from A/c 123456789012345 on 01-11-26")
        assertEquals("Rs 500 debited from A/c XX2345 on 01-11-26", r.text)
        assertEquals("खाता XX7890 में जमा", Redactor.redact("खाता 001234567890 में जमा").text)
    }

    @Test fun upiIdIsMasked() {
        assertEquals("Sent to r***@okhdfcbank", Redactor.redact("Sent to ramesh.k@okhdfcbank").text)
        assertEquals("mail me at a@b.com", Redactor.redact("mail me at a@b.com").text)
    }

    @Test fun phoneNumbersArePreserved() {
        val text = "Call Dr. Mehta at +91 98100 12345"
        assertEquals(text, Redactor.redact(text).text)
    }
}
