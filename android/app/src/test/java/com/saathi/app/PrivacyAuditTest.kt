package com.saathi.app

import com.saathi.app.core.privacy.PrivateKind
import com.saathi.app.core.privacy.PrivateSpans
import com.saathi.app.core.privacy.Redactor
import com.saathi.app.features.inbox.InboxPipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The measured half of docs/PRIVACY_AUDIT.md: every message in fixtures/privacy/messages.json goes down the same
 * path as a real notification (vault tokens → InboxPipeline gate → BackendClient redaction) and we count each secret
 * that would still leave the phone. Writes build/privacy/phone_audit.json for scripts/privacy_audit.py.
 */
class PrivacyAuditTest {
    private fun digits(s: String) = s.filter(Char::isDigit)

    /** A secret leaks if it appears as typed, or (for numbers) if 4+ of its trailing digits appear in a row unmasked. */
    private fun leaks(secret: String, sent: String): Boolean {
        if (secret in sent) return true
        val d = digits(secret)
        return d.length >= 6 && d.takeLast(6) in digits(sent.replace(Regex("⟦[^⟧]*⟧"), ""))
    }

    @Test
    fun noSecretLeavesThePhone() {
        val cases = Json.parseToJsonElement(File(Fixtures.root, "privacy/messages.json").readText()).jsonArray.map { it as JsonObject }
        var secrets = 0
        var leaked = 0
        var sentToCloud = 0
        var keptOnPhone = 0
        var bytesIn = 0
        var bytesOut = 0
        val rows = mutableListOf<String>()
        for (c in cases) {
            val text = c["text"]!!.jsonPrimitive.content
            val contact = c["contact"]?.jsonPrimitive?.booleanOrNull == true
            val extracted = PrivateSpans.extract(text)
            val isOtp = Redactor.detectOtp(text) != null || extracted.spans.any { it.kind == PrivateKind.OTP }
            val tokenized = PrivateSpans.bind(extracted.text, extracted.spans.indices.map { it + 1L })
            val cloud = InboxPipeline.prepareTokenized(c["id"]!!.jsonPrimitive.content, "s", tokenized, isOtp, contact).cloudItem
                ?.let { Redactor.redact(it.text).text } // BackendClient.classify redacts once more
            val caseSecrets = c["secrets"]!!.jsonArray.map { it.jsonPrimitive.content }
            val caseLeaks = if (cloud == null) emptyList() else caseSecrets.filter { leaks(it, cloud) }
            secrets += caseSecrets.size
            leaked += caseLeaks.size
            bytesIn += text.toByteArray().size
            if (cloud == null) keptOnPhone++ else { sentToCloud++; bytesOut += cloud.toByteArray().size }
            rows += """{"id":"${c["id"]!!.jsonPrimitive.content}","source":"${c["source"]!!.jsonPrimitive.content}",""" +
                """"left_phone":${cloud != null},"secrets":${caseSecrets.size},"leaked":${caseLeaks.size},"tokens":${extracted.spans.size}}"""
        }
        val out = File("build/privacy").apply { mkdirs() }
        File(out, "phone_audit.json").writeText(
            """{"messages":${cases.size},"secrets":$secrets,"leaked":$leaked,"sent_to_cloud":$sentToCloud,""" +
                """"kept_on_phone":$keptOnPhone,"bytes_in":$bytesIn,"bytes_out":$bytesOut,"cases":[${rows.joinToString(",")}]}""",
        )
        assertEquals("secrets that would leave the phone: ${rows.filter { "\"leaked\":0" !in it }}", 0, leaked)
    }
}
