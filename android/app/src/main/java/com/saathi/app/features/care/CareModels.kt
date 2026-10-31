package com.saathi.app.features.care

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Wire types for the backend's `/care` routes (docs/CARE.md). Unknown fields are ignored. */

@Serializable
data class CareLocation(
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    val address: String? = null,
    val landmark: String? = null,
)

@Serializable
data class CarePatient(
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val phone: String? = null,
    /** Known conditions from the profile ("type 2 diabetes"), so triage weighs them. */
    val conditions: List<String> = emptyList(),
)

@Serializable
data class CareStart(
    val location: CareLocation? = null,
    val patient: CarePatient = CarePatient(),
    @SerialName("reply_style") val replyStyle: String = "en",
)

@Serializable
data class StartReply(@SerialName("session_id") val sessionId: String)

@Serializable
data class EmergencyInfo(
    val answer: String,
    val reasoning: String = "",
    @SerialName("triggering_red_flags") val triggeringRedFlags: List<String> = emptyList(),
    val acuity: Int = 3,
)

@Serializable
data class Condition(val name: String, val likelihood: String = "medium")

@Serializable
data class IntakeNote(
    @SerialName("note_text") val noteText: String = "",
    @SerialName("chief_complaint") val chiefComplaint: String = "",
    val emergency: EmergencyInfo,
    val specialty: String = "general medicine",
    @SerialName("patient_brief") val patientBrief: String = "",
    @SerialName("possible_conditions") val possibleConditions: List<Condition> = emptyList(),
)

@Serializable
data class IntakeReply(
    val done: Boolean,
    val question: String? = null,
    val route: String? = null,
    val note: IntakeNote? = null,
    @SerialName("safety_flags") val safetyFlags: List<String> = emptyList(),
)

@Serializable
data class CareEvent(val seq: Int, val type: String, val data: JsonObject = JsonObject(emptyMap()))

@Serializable
data class EventsReply(val events: List<CareEvent> = emptyList())

@Serializable
data class Hospital(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val phone: String? = null,
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int = 0,
    @SerialName("eta_minutes") val etaMinutes: Double? = null,
)

@Serializable
data class Slot(val start: String, val end: String? = null)

@Serializable
data class CallResult(
    @SerialName("hospital_id") val hospitalId: String,
    @SerialName("hospital_name") val hospitalName: String,
    @SerialName("call_outcome") val callOutcome: String = "COMPLETED",
    @SerialName("specialty_available") val specialtyAvailable: Boolean? = null,
    @SerialName("doctor_name") val doctorName: String? = null,
    val slots: List<Slot> = emptyList(),
    @SerialName("fee_inr") val feeInr: Int? = null,
    @SerialName("eta_minutes") val etaMinutes: Double? = null,
    val address: String = "",
)

@Serializable
data class Recommendation(
    val primary: CallResult,
    val slot: Slot,
    val alternatives: List<CallResult> = emptyList(),
    @SerialName("spoken_text") val spokenText: String,
)

@Serializable
data class CalendarPayload(
    val title: String,
    val location: String = "",
    val description: String = "",
    @SerialName("start_iso") val startIso: String,
    @SerialName("end_iso") val endIso: String? = null,
    @SerialName("navigate_url") val navigateUrl: String? = null,
)

@Serializable
data class BookingStatus(
    val status: String,
    @SerialName("hospital_id") val hospitalId: String? = null,
    @SerialName("doctor_name") val doctorName: String? = null,
    @SerialName("start_iso") val startIso: String? = null,
    @SerialName("booking_ref") val bookingRef: String? = null,
    val instructions: String? = null,
    val alternatives: List<Slot> = emptyList(),
    val calendar: CalendarPayload? = null,
    @SerialName("call_yourself") val callYourself: String? = null,
)

@Serializable
data class BookRequest(
    @SerialName("hospital_id") val hospitalId: String,
    val slot: Slot,
    val patient: CarePatient? = null,
)
