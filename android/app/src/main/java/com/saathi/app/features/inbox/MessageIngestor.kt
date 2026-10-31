package com.saathi.app.features.inbox

import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.MessageEntity
import com.saathi.app.core.net.ClassifyItem
import com.saathi.app.core.privacy.Redactor
import com.saathi.app.features.scam.ScamLevel
import com.saathi.app.features.scam.ScamReactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

/**
 * Entry point for every captured SMS / notification (SPEC §7.4):
 * OTP check for CallGuard (raw text, local only) → redact → dedupe → scam scan → store → classify
 * non-contact, non-OTP messages (redacted) → reminders. Raw text is never logged or stored.
 */
class MessageIngestor(private val container: AppContainer) {
    private val db get() = container.db
    private val reactor = ScamReactor(container)
    private val lock = Mutex()

    suspend fun ingest(sourcePkg: String, sender: String, text: String, ts: Long) {
        if (text.isBlank()) return
        if (Redactor.detectOtp(text) != null && container.callGuard.inCall) container.callGuard.onOtpArrived()

        val fromContact = container.contacts.isSavedContact(sender)
        val prepared = InboxPipeline.prepare("pending", sender, text, fromContact)

        val id = lock.withLock {
            if (db.messages().countDuplicates(sender, prepared.redactedText, ts - DEDUPE_MS) > 0) return
            db.messages().insert(MessageEntity(
                sourcePkg = sourcePkg, sender = sender, redactedText = prepared.redactedText,
                category = when {
                    prepared.isOtp -> "other"
                    fromContact -> "personal"
                    else -> "other"
                },
                receivedAt = ts, fromContact = fromContact, isOtp = prepared.isOtp,
                classified = !InboxPipeline.mayLeaveDevice(prepared.isOtp, fromContact),
            ))
        }

        val scan = reactor.scanAndCheckLinks(text, fromContact)
        if (scan.level >= ScamLevel.INFO) {
            db.messages().byId(id)?.let { row ->
                db.messages().update(row.copy(risk = scan.level.wire,
                    category = if (scan.level >= ScamLevel.AMBER) "scam_suspected" else row.category))
            }
        }
        reactor.react(id, scan)

        if (prepared.cloudItem != null) classifyPending()
    }

    /** Sends every still-unclassified, cloud-eligible message (already redacted) in one batch. */
    suspend fun classifyPending() {
        val rows = db.messages().unclassified()
            .filter { InboxPipeline.mayLeaveDevice(it.isOtp, it.fromContact) }
        if (rows.isEmpty()) return
        val results = InboxPipeline.classify(container.backend, rows.map { ClassifyItem(it.id.toString(), it.sender, it.redactedText) })
        val now = ZonedDateTime.now()
        for (res in results) {
            val row = rows.firstOrNull { it.id.toString() == res.id } ?: continue
            val keepScam = row.category == "scam_suspected"
            db.messages().update(row.copy(
                category = if (keepScam) row.category else res.category,
                amount = res.amount, dueDate = res.dueDate, merchant = res.merchant, summary = res.summary,
                classified = true,
            ))
            if (!keepScam && res.category != "scam_suspected") {
                ReminderPlanner.forMessage(res, row.id, now).forEach { container.reminders.add(db.reminders(), it) }
            }
        }
    }

    companion object {
        private const val DEDUPE_MS = 2 * 60 * 1000L
    }
}
