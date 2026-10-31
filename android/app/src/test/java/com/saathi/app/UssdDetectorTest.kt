package com.saathi.app

import com.saathi.app.features.scam.ScamLevel
import com.saathi.app.features.scam.UssdDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UssdDetectorTest {

    private fun level(text: String, contact: Boolean = false) = UssdDetector.detect(text, contact)?.level

    @Test fun callForwardingCodesAreRed() {
        listOf("*21*9876543210#", "**21*9876543210#", "*61*98765#", "*62*98765#", "*67*98765#",
            "*401*9876543210", "**61*1#", "**62*1#", "**67*1#", "*004*9876543210#", "**004*98765#",
        ).forEach { assertEquals(it, ScamLevel.RED, level("Please dial $it now")) }
    }

    @Test fun scamDemoCourierMessage() {
        val f = UssdDetector.detect("Courier on hold. Dial *401*9876543210 to reschedule", false)
        assertEquals(ScamLevel.RED, f?.level)
        assertEquals("*401*9876543210", f?.code)
    }

    @Test fun imeiAndTestingCodesAreInfo() {
        assertEquals(ScamLevel.INFO, level("Dial *#06# to see your IMEI"))
        assertEquals(ScamLevel.INFO, level("Open *#*#4636#*#* for phone info"))
    }

    @Test fun upiUssdIsAmberOnlyFromStrangers() {
        assertEquals(ScamLevel.AMBER, level("Dial *99# to get your refund"))
        assertEquals(ScamLevel.INFO, level("Dial *99# to check balance", contact = true))
    }

    @Test fun unknownCodeWithDialWordFromStrangerIsAmber() {
        assertEquals(ScamLevel.AMBER, level("Press *123*45# to claim"))
        assertEquals(ScamLevel.INFO, level("Press *123*45# to claim", contact = true))
    }

    @Test fun plainTextHasNoCode() {
        assertNull(level("Your bill of Rs 1,240 is due on 05-11-2026"))
        assertNull(level("Rating 4.5 * great"))
    }
}
