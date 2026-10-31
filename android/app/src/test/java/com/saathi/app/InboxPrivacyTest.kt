package com.saathi.app

import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentStepRequest
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.BackendUnavailable
import com.saathi.app.core.net.ClassifyItem
import com.saathi.app.core.net.ClassifyResult
import com.saathi.app.core.net.DigestRequest
import com.saathi.app.core.net.Health
import com.saathi.app.core.net.IntentResult
import com.saathi.app.core.net.LinkVerdict
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.net.SendResult
import com.saathi.app.core.net.SttResult
import com.saathi.app.core.net.WelfareConfig
import com.saathi.app.core.privacy.Redactor
import com.saathi.app.features.inbox.InboxPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "OTP messages never reach the network" and "contacts' messages never reach the network" (SPEC §11). */
class InboxPrivacyTest {

    /** Fake backend that records every classify payload and fails the test if an OTP shows up. */
    private class RecordingBackend(private val failClassify: Boolean = false) : BackendApi {
        val sent = mutableListOf<ClassifyItem>()
        override suspend fun classify(items: List<ClassifyItem>): List<ClassifyResult> {
            items.forEach { check(!it.text.contains("OTP", true) && !it.text.contains("482913")) { "OTP leaked: ${it.id}" } }
            sent += items
            if (failClassify) throw BackendUnavailable("offline")
            return items.map { ClassifyResult(it.id, "bill_due", summary = "remote") }
        }
        override suspend fun health() = Health("ok")
        override suspend fun stt(wav: File, languageHint: String, keyterms: List<String>): SttResult = error("unused")
        override suspend fun tts(text: String, style: ReplyStyle): ByteArray = error("unused")
        override suspend fun intent(utterance: String, style: ReplyStyle?): IntentResult = error("unused")
        override suspend fun agentStep(request: AgentStepRequest): AgentAction = error("unused")
        override suspend fun extractAppointment(screenText: String): Appointment = error("unused")
        override suspend fun digest(request: DigestRequest): String = error("unused")
        override suspend fun checkLink(url: String): LinkVerdict = error("unused")
        override suspend fun heartbeat(ts: Long) = Unit
        override suspend fun welfareConfig(config: WelfareConfig) = Unit
        override suspend fun welfareTest() = SendResult()
        override suspend fun notifyFamily(summary: String) = SendResult()
        override suspend fun clinicCallTurn(request: com.saathi.app.core.net.ClinicCallRequest) = error("not used")
        override suspend fun agentOutcome(report: com.saathi.app.core.net.AgentOutcomeReport) = Unit
        override fun liveCameraUrl(): String? = null
        override fun liveVoiceUrl(): String? = null
    }

    private val otpSms = "Your OTP for transaction is 482913. Do not share."
    private val billSms = "Your BSES bill of Rs 1,240 for A/c 123456789012 is due on 05-11-2026."

    @Test fun otpNeverProducesACloudItem() {
        val p = InboxPipeline.prepare("1", "AX-HDFCBK", otpSms, fromContact = false)
        assertTrue(p.isOtp)
        assertNull(p.cloudItem)
        assertEquals(Redactor.OTP_PLACEHOLDER, p.redactedText)
    }

    @Test fun contactMessagesNeverProduceACloudItem() {
        val p = InboxPipeline.prepare("2", "+919810012345", "Mummy, kal 4 baje aaungi. Bill 1240 bhar diya.", fromContact = true)
        assertNull(p.cloudItem)
        assertFalse(InboxPipeline.mayLeaveDevice(isOtp = false, fromContact = true))
    }

    @Test fun cloudItemsCarryOnlyRedactedText() {
        val p = InboxPipeline.prepare("3", "VM-BSES", billSms, fromContact = false)
        val item = assertNotNullAnd(p.cloudItem)
        assertFalse(item.text.contains("123456789012"))
        assertTrue(item.text.contains("XX9012"))
    }

    @Test fun batchDropsAnythingThatStillLooksLikeAnOtp() = runTest {
        val backend = RecordingBackend()
        val leaked = ClassifyItem("9", "AX-HDFCBK", otpSms) // simulate a bug upstream
        val bill = InboxPipeline.prepare("3", "VM-BSES", billSms, false).cloudItem!!
        val results = InboxPipeline.classify(backend, listOf(leaked, bill))
        assertEquals(listOf("3"), backend.sent.map { it.id })
        assertEquals(listOf("3"), results.map { it.id })
    }

    @Test fun backendFailureFallsBackToLocalRules() = runTest {
        val backend = RecordingBackend(failClassify = true)
        val bill = InboxPipeline.prepare("3", "VM-BSES", billSms, false).cloudItem!!
        val results = InboxPipeline.classify(backend, listOf(bill))
        assertEquals("bill_due", results.single().category)
        assertEquals(1240.0, results.single().amount!!, 0.001)
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
