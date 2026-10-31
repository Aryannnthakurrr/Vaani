package com.saathi.app.features.care

import com.saathi.app.util.SaathiJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where the symptom-to-care flow is. ENDED: the conversation closed (or was stopped) without a booking. */
enum class CarePhase { STARTING, INTAKE, THINKING, EMERGENCY, SEARCHING, CALLING, RECOMMEND, BOOKING, BOOKED, NO_OPTIONS, ERROR, ENDED }

data class ChatLine(val fromUser: Boolean, val text: String)

/** One hospital on the live board: its call status, what was said, and the extracted answers. */
data class HospitalRow(
    val hospital: Hospital,
    val calling: Boolean = false,
    val status: String? = null,
    val lines: List<ChatLine> = emptyList(),
    val result: CallResult? = null,
)

data class CareUi(
    val phase: CarePhase = CarePhase.STARTING,
    val chat: List<ChatLine> = emptyList(),
    val note: IntakeNote? = null,
    val route: String? = null,
    val hospitals: List<HospitalRow> = emptyList(),
    val recommendation: Recommendation? = null,
    val booking: BookingStatus? = null,
    /** True only after the user said yes to the calendar question and the event was written. */
    val calendarAdded: Boolean = false,
    val fallback: List<Hospital> = emptyList(),
    val message: String? = null,
    /** A slot a hospital is offering on a call that is still going on; the user's yes books it on that call. */
    val liveAsk: LiveAsk? = null,
    /** The last yes on a call could not be confirmed from that call; the usual recommendation follows. */
    val liveUnclear: Boolean = false,
    /** The user said no to the recommended slot: only the other times stay on the card, each still bookable. */
    val declined: Boolean = false,
    val lastSeq: Int = 0,
)

/** One "book it now?" question while a hospital waits on the line (backend care/live_confirm.py). */
data class LiveAsk(val askId: String, val hospitalName: String, val doctor: String, val slot: String, val fee: String)

/**
 * Folds backend events (docs/CARE.md) into [CareUi]. Pure, so it is JVM-tested. Intake events are ignored here:
 * the intake replies drive the conversation directly.
 */
object CareReducer {
    private fun <T> JsonObject.decode(serializer: KSerializer<T>): T? = runCatching { SaathiJson.decodeFromJsonElement(serializer, this) }.getOrNull()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun apply(ui: CareUi, e: CareEvent): CareUi {
        if (e.seq <= ui.lastSeq) return ui
        val next = when (e.type) {
            "care_status" -> ui.copy(phase = CarePhase.SEARCHING)
            "hospitals_found" -> {
                val list = (e.data["hospitals"])?.let { runCatching { SaathiJson.decodeFromJsonElement(ListSerializer(Hospital.serializer()), it) }.getOrNull() }
                    .orEmpty()
                val calling = e.data["calling"]?.toString().orEmpty()
                ui.copy(phase = CarePhase.CALLING, hospitals = list.map { HospitalRow(it, calling = "\"${it.id}\"" in calling) })
            }
            "call_status" -> ui.updateRow(e.data.str("hospital_id")) { it.copy(calling = true, status = e.data.str("status")) }
            "call_transcript" -> ui.updateRow(e.data.str("hospital_id")) {
                it.copy(lines = (it.lines + ChatLine(e.data.str("speaker") == "hospital", e.data.str("text").orEmpty())).takeLast(12))
            }
            "call_result" -> e.data.decode(CallResult.serializer())?.let { r -> ui.updateRow(r.hospitalId) { it.copy(result = r, calling = true) } } ?: ui
            "recommendation" -> e.data.decode(Recommendation.serializer())?.let { ui.copy(phase = CarePhase.RECOMMEND, recommendation = it) } ?: ui
            "no_options", "care_fallback" -> ui.copy(
                phase = CarePhase.NO_OPTIONS,
                message = e.data.str("message") ?: ui.message,
                fallback = e.data["hospitals"]?.let { runCatching { SaathiJson.decodeFromJsonElement(ListSerializer(Hospital.serializer()), it) }.getOrNull() }
                    ?: ui.fallback,
            )
            "booking_status" -> e.data.decode(BookingStatus.serializer())?.let {
                ui.copy(booking = it, phase = if (it.status == "confirmed") CarePhase.BOOKED else CarePhase.BOOKING)
            } ?: ui
            "live_confirm" -> e.data.str("ask_id")?.let {
                ui.copy(liveAsk = LiveAsk(it, e.data.str("hospital_name").orEmpty(), e.data.str("doctor").orEmpty(),
                    e.data.str("slot").orEmpty(), e.data.str("fee").orEmpty()))
            } ?: ui
            "live_confirm_done" -> if (ui.liveAsk?.askId == e.data.str("ask_id")) ui.copy(liveAsk = null) else ui
            "live_confirm_unclear" -> ui.copy(liveAsk = null, liveUnclear = true)
            "error" -> ui.copy(message = e.data.str("message"), phase = if (ui.phase == CarePhase.BOOKING) ui.phase else CarePhase.ERROR)
            else -> ui
        }
        return next.copy(lastSeq = e.seq)
    }

    private fun CareUi.updateRow(id: String?, change: (HospitalRow) -> HospitalRow): CareUi =
        if (id == null) this else copy(hospitals = hospitals.map { if (it.hospital.id == id) change(it) else it })
}
