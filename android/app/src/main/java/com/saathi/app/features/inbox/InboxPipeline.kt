package com.saathi.app.features.inbox

import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.ClassifyItem
import com.saathi.app.core.net.ClassifyResult
import com.saathi.app.core.privacy.Redactor
import kotlinx.coroutines.withTimeoutOrNull

/** One incoming message after on-device processing. [cloudItem] is null when it must never leave the phone. */
data class PreparedMessage(
    val sender: String,
    val redactedText: String,
    val isOtp: Boolean,
    val fromContact: Boolean,
    val cloudItem: ClassifyItem?,
)

/**
 * The privacy gate for the inbox (SPEC §7.4 "Cloud scope"). Pure so it can be tested on the JVM:
 * OTP messages and messages from saved contacts never produce a cloud item, and cloud items only ever
 * carry redacted text.
 */
object InboxPipeline {

    fun mayLeaveDevice(isOtp: Boolean, fromContact: Boolean): Boolean = !isOtp && !fromContact

    fun prepare(id: String, sender: String, rawText: String, fromContact: Boolean): PreparedMessage {
        val red = Redactor.redact(rawText)
        val cloud = if (mayLeaveDevice(red.isOtp, fromContact)) ClassifyItem(id, sender, red.text) else null
        return PreparedMessage(sender, red.text, red.isOtp, fromContact, cloud)
    }

    /**
     * The same gate for text whose private values are already vault tokens (`⟦P12⟧`, see PrivateSpans). The local
     * copy keeps the tokens so the phone can read the values aloud later; the cloud copy is masked again as a
     * second line of defence, and OTP or contact messages still never produce one.
     */
    fun prepareTokenized(id: String, sender: String, tokenized: String, isOtp: Boolean, fromContact: Boolean): PreparedMessage {
        val cloud = if (mayLeaveDevice(isOtp, fromContact)) ClassifyItem(id, sender, Redactor.redact(tokenized).text) else null
        return PreparedMessage(sender, tokenized, isOtp, fromContact, cloud)
    }

    /**
     * Classifies a batch through the backend, falling back to [LocalClassifier] on any failure.
     * Items are re-filtered here as a second line of defence: anything still looking like an OTP is dropped.
     */
    suspend fun classify(backend: BackendApi, items: List<ClassifyItem>): List<ClassifyResult> {
        val safe = items.filter { it.text != Redactor.OTP_PLACEHOLDER && Redactor.detectOtp(it.text) == null }
        if (safe.isEmpty()) return emptyList()
        val remote = withTimeoutOrNull(8_000) { runCatching { backend.classify(safe) }.getOrNull() }
        val byId = remote.orEmpty().associateBy { it.id }
        return safe.map { byId[it.id] ?: LocalClassifier.classify(it) }
    }
}
