package com.saathi.app.features.care

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

enum class EmergencyPhase { COUNTDOWN, DISPATCHING, TRACKING, ARRIVED, NO_UNITS, CANCELLED }

data class EmergencyUi(
    val phase: EmergencyPhase = EmergencyPhase.COUNTDOWN,
    val secondsLeft: Int = 15,
    val brief: String = "",
    val topCondition: String? = null,
    val askLandmark: Boolean = false,
    val status: String? = null,
    val vehicle: String? = null,
    val crew: String? = null,
    val etaSec: Int? = null,
    val distanceM: Int? = null,
    val patient: Pair<Double, Double>? = null,
    val route: List<List<Double>> = emptyList(),
    val ambulance: Triple<Double, Double, Double>? = null, // lat, lng, heading
    val timeline: List<String> = emptyList(),
    val message: String? = null,
    val familyAlerted: Boolean = false,
)

/**
 * Path A on the phone (workflow §4): siren and countdown, auto-dispatch at zero, live tracking, "I'm OK" and the
 * call-108 fallback. The backend talks to the dispatch API; this only polls the care session's events.
 */
class EmergencyFlow(private val c: AppContainer) {
    private val client = CareClient { c.settings.current }
    private val siren = Siren(c.context)
    private val _ui = MutableStateFlow(EmergencyUi())
    val ui: StateFlow<EmergencyUi> = _ui
    private var countdown: Job? = null
    private var tracker: Job? = null
    private var listener: Job? = null
    private var session: String? = null
    private var note: IntakeNote? = null
    @Volatile var landmark: String = ""

    private val style get() = c.settings.current.replyStyle
    private fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    /** Called by [CareFlow] when the intake routes to A; opens the emergency screen and starts the countdown. */
    suspend fun start(sessionId: String, intake: IntakeNote, location: CareLocation?) {
        session = sessionId
        note = intake
        val lowAccuracy = location == null || (location.accuracyM ?: 1_000.0) > 100.0
        _ui.value = EmergencyUi(
            brief = intake.patientBrief.ifBlank { intake.chiefComplaint },
            topCondition = intake.possibleConditions.firstOrNull()?.name,
            askLandmark = lowAccuracy,
            patient = location?.let { it.lat to it.lng },
        )
        c.assistant.openScreen("emergency")
        val seconds = runCatching { client.emergencyAlert(sessionId) }.getOrDefault(15)
        _ui.update { it.copy(secondsLeft = seconds) }
        c.speaker.say(s("This could be an emergency. I will send an ambulance in $seconds seconds. Say I'm OK, or tap it, to cancel.",
            "यह इमरजेंसी हो सकती है। मैं $seconds सेकंड में एम्बुलेंस भेज दूँगी। रोकने के लिए 'मैं ठीक हूँ' बोलिए या दबाइए।",
            "Yeh emergency ho sakti hai. Main $seconds second mein ambulance bhej dungi. Rokne ke liye 'main theek hoon' boliye ya dabaiye."), style)
        siren.start()
        countdown = c.scope.launch {
            while (_ui.value.secondsLeft > 0 && _ui.value.phase == EmergencyPhase.COUNTDOWN) {
                delay(1_000)
                _ui.update { it.copy(secondsLeft = it.secondsLeft - 1) }
            }
            if (_ui.value.phase == EmergencyPhase.COUNTDOWN) dispatch("countdown")
        }
        listener = c.scope.launch { listenDuringCountdown() }
    }

    /** Voice-first: "I'm OK" cancels and "send it" sends now, same as the buttons. Silence lets the countdown run. */
    private suspend fun listenDuringCountdown() {
        while (_ui.value.phase == EmergencyPhase.COUNTDOWN) {
            siren.duck(true)
            val heard = try {
                runCatching { c.assistant.speech.listen() }.getOrNull()?.transcript
            } finally {
                siren.duck(false)
            }
            if (_ui.value.phase != EmergencyPhase.COUNTDOWN) return
            when (CountdownWords.parse(heard)) {
                CountdownWords.Said.OK -> return imOk()
                CountdownWords.Said.SEND -> return sendNow()
                CountdownWords.Said.NOTHING -> if (heard == null) delay(500) // no mic: don't spin
            }
        }
    }

    fun sendNow() {
        countdown?.cancel()
        c.scope.launch { dispatch("send_now") }
    }

    private suspend fun dispatch(trigger: String) {
        val id = session ?: return
        listener?.cancel()
        siren.stop()
        _ui.update { it.copy(phase = EmergencyPhase.DISPATCHING) }
        runCatching { client.emergencyDispatch(id, trigger, landmark.ifBlank { null }) }
            .onFailure { return noUnits(it.message) }
        c.speaker.say(s("I am requesting an ambulance now. Stay where you are.", "मैं अभी एम्बुलेंस बुला रही हूँ। जहाँ हैं वहीं रहिए।",
            "Main abhi ambulance bula rahi hoon. Jahan hain wahin rahiye."), style)
        tracker?.cancel()
        tracker = c.scope.launch { track(id) }
    }

    private suspend fun track(id: String) {
        var seq = 0
        while (true) {
            val events = runCatching { client.events(id, seq) }.getOrDefault(emptyList())
            for (e in events) {
                seq = e.seq
                when (e.type) {
                    "dispatch_failed" -> return noUnits(null, (e.data["family_alerted"] as? JsonPrimitive)?.booleanOrNull == true)
                    "dispatch_status" -> onStatus(e.data)
                    "ambulance_location" -> onLocation(e.data)
                }
            }
            if (_ui.value.phase in setOf(EmergencyPhase.CANCELLED, EmergencyPhase.NO_UNITS) || _ui.value.status == "COMPLETED") return
            delay(1_000)
        }
    }

    private suspend fun onStatus(d: JsonObject) {
        val status = d.str("status") ?: return
        val vehicle = (d["vehicle"] as? JsonObject)?.str("registration")
        val crew = (d["crew"] as? JsonObject)?.let { crew ->
            listOfNotNull(crew["driver"]?.jsonObject?.str("name"), crew["paramedic"]?.jsonObject?.str("name")).joinToString(", ")
        }
        val route = d["route"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.let {
            runCatching { SaathiJson.decodeFromJsonElement(ListSerializer(ListSerializer(Double.serializer())), it) }.getOrNull()
        }
        _ui.update {
            it.copy(
                phase = when (status) {
                    "CANCELLED" -> EmergencyPhase.CANCELLED
                    "RECEIVED", "ASSIGNED", "EN_ROUTE" -> EmergencyPhase.TRACKING
                    else -> EmergencyPhase.ARRIVED // at the scene or later: help is with the patient
                },
                status = status, vehicle = vehicle ?: it.vehicle, crew = crew?.ifBlank { null } ?: it.crew,
                route = route ?: it.route, timeline = (it.timeline + status).distinct(),
            )
        }
        when (status) {
            "ASSIGNED" -> c.speaker.say(s("An ambulance is assigned${vehicle?.let { ", number $it" }.orEmpty()}.",
                "एम्बुलेंस मिल गई है${vehicle?.let { ", नंबर $it" }.orEmpty()}।", "Ambulance mil gayi hai${vehicle?.let { ", number $it" }.orEmpty()}."), style)
            "EN_ROUTE" -> c.speaker.say(s("The ambulance is on the way.", "एम्बुलेंस रास्ते में है।", "Ambulance raste mein hai."), style)
            "ARRIVED_AT_SCENE" -> c.speaker.say(s("The ambulance has arrived.", "एम्बुलेंस पहुँच गई है।", "Ambulance pahunch gayi hai."), style)
            "EN_ROUTE_TO_HOSPITAL" -> c.speaker.say(s("The ambulance is taking the patient to the hospital.",
                "एम्बुलेंस मरीज़ को अस्पताल ले जा रही है।", "Ambulance patient ko hospital le ja rahi hai."), style)
            "ARRIVED_AT_HOSPITAL" -> c.speaker.say(s("The patient has reached the hospital.", "मरीज़ अस्पताल पहुँच गए हैं।",
                "Patient hospital pahunch gaye hain."), style)
        }
    }

    private fun onLocation(d: JsonObject) {
        val lat = d.num("lat") ?: return
        val lng = d.num("lng") ?: return
        _ui.update {
            it.copy(ambulance = Triple(lat, lng, d.num("heading") ?: 0.0),
                etaSec = (d["eta_sec"] as? JsonPrimitive)?.intOrNull, distanceM = (d["distance_m"] as? JsonPrimitive)?.intOrNull)
        }
    }

    /** @param familyAlerted true only when the backend's message reached at least one family member. */
    private suspend fun noUnits(detail: String?, familyAlerted: Boolean = false) {
        siren.stop()
        _ui.update { it.copy(phase = EmergencyPhase.NO_UNITS, message = detail, familyAlerted = familyAlerted) }
        c.speaker.say(s("No ambulance is available. Call 108 now.", "कोई एम्बुलेंस उपलब्ध नहीं है। अभी 108 पर फ़ोन कीजिए।",
            "Koi ambulance available nahi hai. Abhi 108 par call kijiye.") +
            if (familyAlerted) " " + s("I have alerted your family.", "मैंने आपके परिवार को बता दिया है।", "Maine aapke family ko bata diya hai.") else "",
            style)
    }

    /** "I'm OK": stops the siren, cancels any dispatch, then offers to find a doctor instead (Path B). */
    fun imOk() {
        countdown?.cancel()
        listener?.cancel()
        tracker?.cancel()
        siren.stop()
        val id = session ?: return
        _ui.update { it.copy(phase = EmergencyPhase.CANCELLED) }
        c.scope.launch {
            runCatching { client.emergencyCancel(id) }
            note?.let { c.care.offerDoctor(id, it) }
        }
    }

    fun call108(context: Context) {
        siren.stop()
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun close() {
        countdown?.cancel()
        listener?.cancel()
        tracker?.cancel()
        siren.stop()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/** What someone says during the countdown. A negation ("not OK", "don't cancel") never cancels. */
object CountdownWords {
    enum class Said { OK, SEND, NOTHING }

    private val NOT_OK = listOf("not ok", "not okay", "not fine", "theek nahi", "thik nahi", "ठीक नहीं", "cancel mat", "mat cancel",
        "don't cancel", "dont cancel", "कैंसल मत")
    private val OK = listOf("i'm ok", "im ok", "i am ok", "i'm okay", "im okay", "i am okay", "i'm fine", "i am fine",
        "theek hoon", "thik hoon", "theek hun", "thik hun", "theek hu", "ठीक हूँ", "ठीक हूं", "cancel", "कैंसल", "mat bhejo",
        "mat bulao", "मत भेजो", "मत बुलाओ", "zarurat nahi", "zaroorat nahi", "ज़रूरत नहीं", "जरूरत नहीं", "roko", "rokiye", "रोको")
    private val SEND = listOf("send", "bhejo", "bhejiye", "bulao", "bulaiye", "jaldi", "help", "bachao", "भेजो", "भेजिए",
        "बुलाओ", "जल्दी", "बचाओ")

    fun parse(transcript: String?): Said {
        val t = transcript?.lowercase()?.replace('’', '\'')?.ifBlank { null } ?: return Said.NOTHING
        return when {
            NOT_OK.any { it in t } -> Said.NOTHING
            OK.any { it in t } -> Said.OK
            SEND.any { it in t } -> Said.SEND
            else -> Said.NOTHING
        }
    }
}
