package com.saathi.app.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reply style shared with the backend: en | hi | hinglish. */
enum class ReplyStyle(val wire: String) {
    EN("en"), HI("hi"), HINGLISH("hinglish");

    companion object {
        fun from(wire: String?): ReplyStyle = entries.firstOrNull { it.wire == wire } ?: EN
    }
}

enum class AgentMode { ACT, GUIDE }

/** The agent action schema (docs/engineering/CONTRACTS.md). Flat with an [action] discriminator. */
@Serializable
data class AgentAction(
    val action: String,
    val id: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val direction: String? = null,
    @SerialName("package") val pkg: String? = null,
    val uri: String? = null,
    val ms: Long? = null,
    val question: String? = null,
    val summary: String? = null,
    val then: AgentAction? = null,
    val instruction: String? = null,
    val gesture: String? = null,
    val message: String? = null,
    val reason: String? = null,
) {
    companion object {
        const val TAP = "tap"
        const val TAP_XY = "tap_xy"
        const val TYPE = "type"
        const val SCROLL = "scroll"
        const val BACK = "back"
        const val HOME = "home"
        const val OPEN_APP = "open_app"
        const val OPEN_DEEPLINK = "open_deeplink"
        const val WAIT = "wait"
        const val ASK_USER = "ask_user"
        const val CONFIRM = "confirm"
        const val HIGHLIGHT = "highlight"
        const val DONE = "done"
        const val FAIL = "fail"

        val ALL = setOf(TAP, TAP_XY, TYPE, SCROLL, BACK, HOME, OPEN_APP, OPEN_DEEPLINK, WAIT, ASK_USER,
            CONFIRM, HIGHLIGHT, DONE, FAIL)

        fun fail(message: String, reason: String = "client") = AgentAction(FAIL, message = message, reason = reason)
    }
}

@Serializable
data class HistoryItem(val action: AgentAction, val reason: String? = null)

@Serializable
data class AgentStepRequest(
    val goal: String,
    val mode: String,
    val language: String,
    @SerialName("screen_text") val screenText: String,
    @SerialName("package") val pkg: String,
    @SerialName("screenshot_b64") val screenshotB64: String? = null,
    val history: List<HistoryItem> = emptyList(),
    @SerialName("task_hint") val taskHint: String? = null,
    /** Same for every step of one agent run, so the backend can learn routines from whole runs. */
    @SerialName("run_id") val runId: String? = null,
)

/** End of an agent run (docs/features/LEARNING.md §1): successful runs are what skills are compiled from. */
@Serializable
data class AgentOutcomeReport(
    @SerialName("run_id") val runId: String,
    @SerialName("task_hint") val taskHint: String? = null,
    val goal: String,
    /** done | failed | cancelled */
    val outcome: String,
    val message: String? = null,
)

@Serializable
data class SttResult(
    val transcript: String,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("reply_style") val replyStyle: String? = null,
)

@Serializable
data class TtsRequest(val text: String, @SerialName("reply_style") val replyStyle: String, val voice: String? = null)

@Serializable
data class IntentRequest(val utterance: String, @SerialName("reply_style") val replyStyle: String? = null)

@Serializable
data class Slots(
    val destination: String? = null,
    val product: String? = null,
    val specialty: String? = null,
    val doctor: String? = null,
    val area: String? = null,
    val time: String? = null,
    val date: String? = null,
    val service: String? = null,
    val quantity: Int? = null,
    /** book_cab: "auto" or "bike" only when the user asked for one; null (or "car") means a car. */
    val vehicle: String? = null,
)

@Serializable
data class IntentResult(
    val intent: String,
    val slots: Slots = Slots(),
    @SerialName("reply_style") val replyStyle: String? = null,
    @SerialName("reply_text") val replyText: String? = null,
)

@Serializable
data class ClassifyItem(val id: String, val sender: String, val text: String)

@Serializable
data class ClassifyRequest(val messages: List<ClassifyItem>)

@Serializable
data class ClassifyResult(
    val id: String,
    val category: String,
    val amount: Double? = null,
    @SerialName("due_date") val dueDate: String? = null,
    val merchant: String? = null,
    val summary: String? = null,
)

@Serializable
data class ClassifyResponse(val results: List<ClassifyResult> = emptyList())

@Serializable
data class ExtractRequest(@SerialName("screen_text") val screenText: String)

@Serializable
data class Appointment(
    val doctor: String? = null,
    val clinic: String? = null,
    val address: String? = null,
    val phone: String? = null,
    @SerialName("start_iso") val startIso: String? = null,
    val fee: String? = null,
)

@Serializable
data class DigestItem(
    val category: String,
    val merchant: String? = null,
    val amount: Double? = null,
    @SerialName("due_date") val dueDate: String? = null,
    val summary: String,
)

@Serializable
data class DigestRequest(
    val items: List<DigestItem>,
    @SerialName("reply_style") val replyStyle: String,
    @SerialName("user_name") val userName: String? = null,
    val today: String? = null,
)

@Serializable
data class DigestResponse(val text: String)

@Serializable
data class LinkCheckRequest(val url: String)

@Serializable
data class LinkVerdict(
    val url: String,
    @SerialName("final_url") val finalUrl: String? = null,
    val verdict: String,
    @SerialName("reason_simple") val reasonSimple: String? = null,
    @SerialName("reason_simple_hi") val reasonSimpleHi: String? = null,
    val signals: List<String> = emptyList(),
    val cached: Boolean = false,
)

@Serializable
data class Heartbeat(@SerialName("device_id") val deviceId: String, val ts: Long)

@Serializable
data class FamilyContact(val name: String, @SerialName("telegram_chat_id") val telegramChatId: String)

@Serializable
data class WelfareConfig(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_name") val userName: String,
    val contacts: List<FamilyContact>,
    @SerialName("check_time") val checkTime: String,
)

@Serializable
data class DeviceOnly(@SerialName("device_id") val deviceId: String)

@Serializable
data class FamilyNotify(@SerialName("device_id") val deviceId: String, val summary: String)

@Serializable
data class SendResult(val text: String? = null, val sent: List<String> = emptyList(), val logged: Boolean = false)

@Serializable
data class Health(
    val status: String,
    val mock: Boolean = true,
    val sarvam: Boolean = false,
    val telegram: Boolean = false,
)

@Serializable
data class ProductCard(
    val name: String? = null,
    val mrp: String? = null,
    val quantity: String? = null,
    @SerialName("expiry_iso") val expiryIso: String? = null,
    @SerialName("expiry_status") val expiryStatus: String = "unknown",
    val confidence: Double? = null,
    /** Sugar or salt warning for this user's diabetes or BP, decided by fixed code on the backend (app/abilities/food.py). */
    @SerialName("health_warning") val healthWarning: String? = null,
    @SerialName("health_level") val healthLevel: String? = null,
) {
    /** Printed MRP as "₹140", whether the relay sent "₹140", "140" or 140.0. */
    val mrpText: String?
        get() = mrp?.trim()?.removePrefix("₹")?.removePrefix("Rs")?.trim()?.let { raw ->
            val n = raw.replace(",", "").toDoubleOrNull()
            "₹" + if (n != null && n % 1.0 == 0.0) n.toLong().toString() else raw
        }
}

@Serializable
data class CallLine(val role: String, val text: String)

@Serializable
data class ClinicCallRequest(
    @SerialName("patient_name") val patientName: String,
    val appointment: Appointment,
    @SerialName("reply_style") val replyStyle: String,
    val transcript: List<CallLine>,
)

@Serializable
data class ClinicCallTurn(
    val say: String,
    val done: Boolean = false,
    val confirmed: Boolean? = null,
    @SerialName("waiting_minutes") val waitingMinutes: Int? = null,
    val summary: String? = null,
)
