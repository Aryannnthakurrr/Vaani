package com.saathi.app

import com.saathi.app.core.net.ClassifyItem
import com.saathi.app.features.inbox.LocalClassifier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LocalClassifierTest {
    private val today = LocalDate.of(2026, 10, 31)

    private fun classify(text: String, sender: String = "VM-BILLER") =
        LocalClassifier.classify(ClassifyItem("1", sender, text), today)

    @Test fun bill() {
        val r = classify("Your BSES electricity bill of Rs 1,240 is due on 05-11-2026.")
        assertEquals("bill_due", r.category)
        assertEquals(1240.0, r.amount!!, 0.001)
        assertEquals("2026-11-05", r.dueDate)
        assertEquals("BSES", r.merchant)
    }

    @Test fun renewal() {
        val r = classify("Your Netflix membership will renew on 2 Nov for Rs 199.")
        assertEquals("subscription_renewal", r.category)
        assertEquals(199.0, r.amount!!, 0.001)
        assertEquals("2026-11-02", r.dueDate)
        assertEquals("Netflix", r.merchant)
    }

    @Test fun appointment() {
        val r = classify("Appointment confirmed with Dr. Mehta on 3 Nov 2026 at 4 PM")
        assertEquals("appointment", r.category)
        assertEquals("2026-11-03", r.dueDate)
    }

    @Test fun delivery() {
        val r = classify("Your Amazon order is out for delivery today")
        assertEquals("delivery", r.category)
        assertEquals("2026-10-31", r.dueDate)
        assertEquals("Amazon", r.merchant)
    }

    @Test fun promo() {
        assertEquals("promo", classify("Flat 50% off on all shoes! Sale ends Sunday. Shop now").category)
    }

    @Test fun paymentDone() {
        val r = classify("Rs 500 debited from A/c XX1234 to Zomato on 30-10-26")
        assertEquals("payment_done", r.category)
        assertEquals(500.0, r.amount!!, 0.001)
        assertEquals("2026-10-30", r.dueDate)
    }

    @Test fun scam() {
        assertEquals("scam_suspected", classify("Dear customer, your KYC will expire today. Click sbi-kyc.xyz").category)
        assertEquals("scam_suspected", classify("Courier on hold. Dial *401*9876543210 to reschedule").category)
    }

    @Test fun hindiBill() {
        val r = classify("आपका बिजली का बिल ₹850 है, अंतिम तिथि 10/11/2026")
        assertEquals("bill_due", r.category)
        assertEquals(850.0, r.amount!!, 0.001)
        assertEquals("2026-11-10", r.dueDate)
    }

    @Test fun rupeeFormatting() {
        assertEquals("1,240", LocalClassifier.formatRupees(1240.0))
        assertEquals("1,25,000", LocalClassifier.formatRupees(125000.0))
        assertEquals("99.50", LocalClassifier.formatRupees(99.5))
    }

    @Test fun extractsPractoConfirmation() {
        val screen = """
            package=com.practo.fabric title="Appointment confirmed" secure=false
            [1] TextView "Appointment confirmed" bounds=(40,300,1040,420)
            [2] TextView "Dr. Anil Mehta, Cardiologist" bounds=(40,420,1040,540)
            [3] TextView "Mehta Heart Clinic, C-12 Sector 18, Noida 201301" bounds=(40,540,1040,660)
            [4] TextView "Clinic phone: +91 98100 12345" bounds=(40,660,1040,780)
            [5] TextView "Today, 4:00 PM · Fee ₹800" bounds=(40,780,1040,900)
        """.trimIndent()
        val a = LocalClassifier.extractAppointment(screen, today, ZoneId.of("Asia/Kolkata"))
        assertEquals("Dr. Anil Mehta", a.doctor)
        assertEquals("Mehta Heart Clinic", a.clinic)
        assertEquals("C-12 Sector 18, Noida 201301", a.address)
        assertEquals("+91 98100 12345", a.phone)
        assertEquals("2026-10-31T16:00+05:30", a.startIso)
        assertEquals("₹800", a.fee)
    }
}
