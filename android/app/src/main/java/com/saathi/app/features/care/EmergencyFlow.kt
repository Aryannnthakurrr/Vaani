package com.saathi.app.features.care

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

/** Why no ambulance could be requested; each gets its own words on the red page. */
enum class DispatchFailure { NO_UNITS, NO_LOCATION, UNREACHABLE }

data class EmergencyUi(
    val phase: EmergencyPhase = EmergencyPhase.COUNTDOWN,
    val secondsLeft: Int = 15,
    /** The countdown's starting length, for the ring on the countdown screen. */
    val totalSeconds: Int = 15,
    val brief: String = "",
    val topCondition: String? = null,
    val askLandmark: Boolean = false,
    val status: String? = null,
    val vehicle: String? = null,
    /** The dispatch API's fleet id ("AMB-5489"), shown in the chip like Care Compass. */
    val vehicleId: String? = null,
    val crew: String? = null,
    /** "BLS" or "ALS" from the dispatch API. */
    val vehicleType: String? = null,
    val driver: String? = null,
    val paramedic: String? = null,
    /** The first distance reported, so the progress bar shows how much of the trip is done. */
    val startDistanceM: Int? = null,
    /** A short place name for the location chip ("Rohini"). */
    val area: String? = null,
    val etaSec: Int? = null,
    val distanceM: Int? = null,
    val patient: Pair<Double, Double>? = null,
    val route: List<List<Double>> = emptyList(),
    val ambulance: Triple<Double, Double, Double>? = null, // lat, lng, heading
    val timeline: List<String> = emptyList(),
    val message: String? = null,
    val failure: DispatchFailure = DispatchFailure.NO_UNITS,
    val familyAlerted: Boolean = false,
    /** "I'm OK" after dispatch: the cancel request is on its way. */
    val cancelling: Boolean = false,
    /** The cancel request failed: the ambulance may still come. */
    val cancelFailed: Boolean = false,
    /** The doctor flow takes over: the screen steps back so the Doctor screen opens in its place. */
    val handedOver: Boolean = false,
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
    private var intro: Job? = null
    private var tracker: Job? = null
    private var listener: Job? = null
    private var dispatchJob: Job? = null
    private var dispatchRequest: Deferred<Result<Unit>>? = null
    private var handover: Job? = null
    @Volatile private var session: String? = null
    private var note: IntakeNote? = null
    @Volatile var landmark: String = ""

    // One dispatch per emergency, and none once "I'm OK" was said; both flags change together under [lock].
    private val lock = Any()
    private var dispatched = false
    private var cancelled = false

    /** Set by the emergency screen while it is shown, so tracking never runs where nobody can see it. */
    @Volatile var screenShown = false

    private val style get() = c.settings.current.replyStyle
    private fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    /**
     * Called by [CareFlow] when the intake routes to A; opens the emergency screen and starts the countdown.
     * @param immediate the user asked for an ambulance outright: dispatch now, with no countdown to wait through,
     * unless the location is rough: then a short window to add a landmark first.
     */
    suspend fun start(sessionId: String, intake: IntakeNote, location: CareLocation?, immediate: Boolean = false) {
        close() // an earlier emergency's jobs must never write into this one
        synchronized(lock) { dispatched = false; cancelled = false }
        session = sessionId
        note = intake
        landmark = ""
        val lowAccuracy = location == null || (location.accuracyM ?: 1_000.0) > 100.0
        val seconds = if (immediate) LANDMARK_SEC else DEFAULT_SEC
        _ui.value = EmergencyUi(
            secondsLeft = seconds, totalSeconds = seconds,
            // Only the clinical brief: the raw words ("send ambulance now") are a request, not symptoms.
            brief = intake.patientBrief,
            topCondition = intake.possibleConditions.firstOrNull()?.name,
            askLandmark = lowAccuracy,
            patient = location?.let { it.lat to it.lng },
            area = location?.address?.let(::shortArea),
        )
        c.assistant.openScreen("emergency")
        // Without any location a landmark can't help (the backend needs coordinates), so an outright request goes at once.
        if (immediate && (location == null || !lowAccuracy)) return requestDispatch("send_now")
        // The number counts down in real time from the first second; the server call and the spoken intro run alongside.
        val begun = android.os.SystemClock.elapsedRealtime()
        countdown = c.scope.launch {
            while (_ui.value.secondsLeft > 0 && _ui.value.phase == EmergencyPhase.COUNTDOWN) {
                delay(1_000)
                _ui.update { if (it.phase == EmergencyPhase.COUNTDOWN) it.copy(secondsLeft = it.secondsLeft - 1) else it }
            }
            if (_ui.value.phase == EmergencyPhase.COUNTDOWN) requestDispatch(if (immediate) "send_now" else "countdown")
        }
        if (!immediate) c.scope.launch {
            // The server may set a different length; keep the time already gone by.
            val total = runCatching { client.emergencyAlert(sessionId) }.getOrNull() ?: return@launch
            val gone = ((android.os.SystemClock.elapsedRealtime() - begun) / 1_000).toInt()
            _ui.update { if (it.phase == EmergencyPhase.COUNTDOWN && session == sessionId) it.copy(totalSeconds = total, secondsLeft = (total - gone).coerceAtLeast(1)) else it }
        }
        intro = c.scope.launch {
            val left = _ui.value.secondsLeft
            if (immediate) {
                c.speaker.say(s("I will send the ambulance in $left seconds. If you can, type a landmark near you so they find you, or tap Send now.",
                    "मैं $left सेकंड में एम्बुलेंस भेज दूँगी। हो सके तो पास की कोई पहचान की जगह लिखिए, या 'अभी भेजें' दबाइए।",
                    "Main $left second mein ambulance bhej dungi. Ho sake to paas ki koi pehchaan ki jagah likhiye, ya 'Abhi bhejein' dabaiye."), style)
            } else {
                c.speaker.say(s("This could be an emergency. I will send an ambulance in $left seconds. Say I'm OK, or tap it, to cancel.",
                    "यह इमरजेंसी हो सकती है। मैं $left सेकंड में एम्बुलेंस भेज दूँगी। रोकने के लिए 'मैं ठीक हूँ' बोलिए या दबाइए।",
                    "Yeh emergency ho sakti hai. Main $left second mein ambulance bhej dungi. Rokne ke liye 'main theek hoon' boliye ya dabaiye."), style)
                // Only if nobody cancelled or sent while the intro was spoken; checked again after, as a tap can land in between.
                if (_ui.value.phase == EmergencyPhase.COUNTDOWN) siren.start()
                if (_ui.value.phase != EmergencyPhase.COUNTDOWN) siren.stop()
            }
            if (_ui.value.phase == EmergencyPhase.COUNTDOWN) listener = c.scope.launch { listenDuringCountdown() }
        }
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

    fun sendNow() = requestDispatch("send_now")

    /** Sends the dispatch request once; a second tap, a spoken "send" or the countdown reaching zero does nothing more. */
    private fun requestDispatch(trigger: String) {
        val id = session ?: return
        synchronized(lock) {
            if (dispatched || cancelled) return
            dispatched = true
        }
        _ui.update { it.copy(phase = EmergencyPhase.DISPATCHING) } // before the siren stops: see imOk()
        countdown?.cancel()
        intro?.cancel()
        listener?.cancel()
        siren.stop()
        val request = c.scope.async { runCatching { client.emergencyDispatch(id, trigger, landmark.ifBlank { null }) } }
        dispatchRequest = request
        dispatchJob = c.scope.launch {
            // Not "no ambulance available": the request itself never got through.
            if (request.await().isFailure) return@launch noUnits(DispatchFailure.UNREACHABLE)
            if (synchronized(lock) { cancelled }) return@launch // "I'm OK" while the request was on its way; imOk() cancels it
            c.speaker.say(s("I am requesting an ambulance now. Stay where you are.", "मैं अभी एम्बुलेंस बुला रही हूँ। जहाँ हैं वहीं रहिए।",
                "Main abhi ambulance bula rahi hoon. Jahan hain wahin rahiye."), style)
            tracker?.cancel()
            tracker = c.scope.launch { track(id) }
        }
    }

    private suspend fun track(id: String) {
        var seq = 0
        while (session == id) {
            // Long-poll: the server answers the moment an update exists (the ambulance moves every second).
            val events = runCatching { client.events(id, seq, waitSec = 15) }.getOrElse { delay(1_000); emptyList() }
            if (session != id) return
            for (e in events) {
                seq = e.seq
                when (e.type) {
                    "dispatch_failed" -> return noUnits(
                        when ((e.data["reason"] as? JsonPrimitive)?.contentOrNull) {
                            "NO_UNITS_AVAILABLE" -> DispatchFailure.NO_UNITS
                            "NO_LOCATION" -> DispatchFailure.NO_LOCATION
                            else -> DispatchFailure.UNREACHABLE
                        },
                        (e.data["family_alerted"] as? JsonPrimitive)?.booleanOrNull == true)
                    "dispatch_status" -> onStatus(e.data)
                    "ambulance_location" -> onLocation(e.data)
                }
            }
            if (_ui.value.phase in setOf(EmergencyPhase.CANCELLED, EmergencyPhase.NO_UNITS) || _ui.value.status == "COMPLETED") return
        }
    }

    private suspend fun onStatus(d: JsonObject) {
        val status = d.str("status") ?: return
        val vehicle = (d["vehicle"] as? JsonObject)?.str("registration")
        val crewObj = d["crew"] as? JsonObject
        val driver = crewObj?.get("driver")?.jsonObject?.str("name")
        val paramedic = crewObj?.get("paramedic")?.jsonObject?.str("name")
        val crew = listOfNotNull(driver, paramedic).joinToString(", ")
        val vehicleType = (d["vehicle"] as? JsonObject)?.str("type")
        val vehicleId = (d["vehicle"] as? JsonObject)?.str("vehicle_id")
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
                status = status, vehicle = vehicle ?: it.vehicle, crew = crew.ifBlank { null } ?: it.crew,
                vehicleType = vehicleType ?: it.vehicleType, vehicleId = vehicleId ?: it.vehicleId, driver = driver ?: it.driver, paramedic = paramedic ?: it.paramedic,
                route = route ?: it.route, timeline = (it.timeline + status).distinct(),
            )
        }
        val line = when (status) {
            "ASSIGNED" -> s("An ambulance is assigned${vehicle?.let { ", number $it" }.orEmpty()}.",
                "एम्बुलेंस मिल गई है${vehicle?.let { ", नंबर $it" }.orEmpty()}।", "Ambulance mil gayi hai${vehicle?.let { ", number $it" }.orEmpty()}.")
            "EN_ROUTE" -> s("The ambulance is on the way.", "एम्बुलेंस रास्ते में है।", "Ambulance raste mein hai.")
            "ARRIVED_AT_SCENE" -> s("The ambulance has arrived.", "एम्बुलेंस पहुँच गई है।", "Ambulance pahunch gayi hai.")
            "EN_ROUTE_TO_HOSPITAL" -> s("The ambulance is taking the patient to the hospital.",
                "एम्बुलेंस मरीज़ को अस्पताल ले जा रही है।", "Ambulance patient ko hospital le ja rahi hai.")
            "ARRIVED_AT_HOSPITAL" -> s("The patient has reached the hospital.", "मरीज़ अस्पताल पहुँच गए हैं।",
                "Patient hospital pahunch gaye hain.")
            else -> null
        } ?: return
        // An update spoken while another screen is up brings the tracking screen back, so it is never left unseen.
        if (!screenShown) c.assistant.openScreen("emergency")
        c.speaker.say(line, style)
    }

    private fun onLocation(d: JsonObject) {
        val lat = d.num("lat") ?: return
        val lng = d.num("lng") ?: return
        _ui.update {
            val dist = (d["distance_m"] as? JsonPrimitive)?.intOrNull
            it.copy(ambulance = Triple(lat, lng, d.num("heading") ?: 0.0),
                etaSec = (d["eta_sec"] as? JsonPrimitive)?.intOrNull, distanceM = dist,
                startDistanceM = it.startDistanceM ?: dist?.takeIf { m -> m > 0 })
        }
    }

    /** @param familyAlerted true only when the backend's message reached at least one family member. */
    private suspend fun noUnits(failure: DispatchFailure, familyAlerted: Boolean = false) {
        if (synchronized(lock) { cancelled }) return // "I'm OK" already ended this emergency
        siren.stop()
        _ui.update { it.copy(phase = EmergencyPhase.NO_UNITS, failure = failure, familyAlerted = familyAlerted) }
        c.speaker.say(when (failure) {
            DispatchFailure.NO_UNITS -> s("No ambulance is available. Call 108 now.", "कोई एम्बुलेंस उपलब्ध नहीं है। अभी 108 पर फ़ोन कीजिए।",
                "Koi ambulance available nahi hai. Abhi 108 par call kijiye.")
            DispatchFailure.NO_LOCATION -> s("I could not find your location to send an ambulance. Call 108 now.",
                "एम्बुलेंस भेजने के लिए मुझे आपकी लोकेशन नहीं मिली। अभी 108 पर फ़ोन कीजिए।",
                "Ambulance bhejne ke liye mujhe aapki location nahi mili. Abhi 108 par call kijiye.")
            DispatchFailure.UNREACHABLE -> s("I could not request an ambulance. Call 108 now.", "मैं एम्बुलेंस नहीं बुला पाई। अभी 108 पर फ़ोन कीजिए।",
                "Main ambulance nahi bula payi. Abhi 108 par call kijiye.")
        } + if (familyAlerted) " " + s("I have alerted your family.", "मैंने आपके परिवार को बता दिया है।", "Maine aapke family ko bata diya hai.") else "",
            style)
    }

    /**
     * "I'm OK": stops the siren and the countdown, cancels any dispatch, and only says "Cancelled" once that is true.
     * Then, after the confirmation has been seen, offers to find a doctor instead (Path B).
     * @param offerDoctor false when the user left the screen: cancel, but don't pull them back into the doctor flow.
     */
    fun imOk(offerDoctor: Boolean = true) {
        val id = session ?: return
        val ui = _ui.value
        if (ui.cancelling || ui.phase == EmergencyPhase.CANCELLED || ui.phase == EmergencyPhase.NO_UNITS) return
        val wasDispatched = synchronized(lock) { cancelled = true; dispatched }
        // Nothing was sent: the countdown is stopped on the phone, so "Cancelled" is true at once. The phase changes
        // before the siren stops, so an intro finishing just now sees it and doesn't start the siren after all.
        if (!wasDispatched) _ui.update { it.copy(phase = EmergencyPhase.CANCELLED) }
        countdown?.cancel()
        intro?.cancel()
        listener?.cancel()
        siren.stop()
        c.speaker.stop() // the countdown's intro, if it is still being spoken
        handover?.cancel()
        if (!wasDispatched) {
            c.scope.launch { runCatching { client.emergencyCancel(id) } } // the server only marks the countdown stopped
            handover = c.scope.launch { showCancelled(id, offerDoctor) }
            return
        }
        _ui.update { it.copy(cancelling = true, cancelFailed = false) }
        handover = c.scope.launch {
            // A dispatch still on its way is cancelled after it lands, so the cancel can't overtake it.
            val sent = dispatchRequest?.await()?.isSuccess == true
            val ok = !sent || runCatching { client.emergencyCancel(id) }.getOrDefault(false)
            if (!ok) {
                // The ambulance may still come: tracking carries on, and the screen says so with "Call 108".
                _ui.update { it.copy(cancelling = false, cancelFailed = true) }
                c.speaker.say(s("I could not cancel the ambulance. It may still come. Call 108 if you need to.",
                    "मैं एम्बुलेंस रद्द नहीं कर पाई। वह अब भी आ सकती है। ज़रूरत हो तो 108 पर फ़ोन कीजिए।",
                    "Main ambulance cancel nahi kar payi. Woh ab bhi aa sakti hai. Zaroorat ho to 108 par call kijiye."), style)
                return@launch
            }
            tracker?.cancel()
            _ui.update { it.copy(phase = EmergencyPhase.CANCELLED, cancelling = false, cancelFailed = false) }
            showCancelled(id, offerDoctor)
        }
    }

    /** The Cancelled screen stays up for a moment before the Doctor screen takes its place. */
    private suspend fun showCancelled(id: String, offerDoctor: Boolean) {
        c.speaker.say(s("Cancelled. No ambulance is coming.", "रद्द कर दिया। कोई एम्बुलेंस नहीं आ रही।", "Cancel kar diya. Koi ambulance nahi aa rahi."), style)
        if (!offerDoctor) return
        delay(CANCELLED_SHOW_MS)
        val n = note ?: return
        if (session != id || _ui.value.phase != EmergencyPhase.CANCELLED) return
        _ui.update { it.copy(handedOver = true) }
        delay(HANDOVER_MS) // the emergency screen steps back first, so the Doctor screen beneath is reused
        c.care.offerDoctor(id, n)
    }

    /**
     * The system back gesture. During the countdown it is "I'm OK"; while an ambulance is coming it never cancels and
     * never leaves (there is no other way back to this screen), it only says how to cancel.
     * @return true when the screen should close.
     */
    fun back(): Boolean {
        val ui = _ui.value
        return when {
            ui.phase == EmergencyPhase.COUNTDOWN -> { imOk(); false }
            ui.phase == EmergencyPhase.CANCELLED || ui.phase == EmergencyPhase.NO_UNITS ||
                ui.status == "ARRIVED_AT_HOSPITAL" || ui.status == "COMPLETED" -> { close(); true }
            else -> {
                c.scope.launch {
                    c.speaker.say(if (ui.phase == EmergencyPhase.ARRIVED) s("Help is with you. Stay with the crew.",
                        "मदद आपके पास है। टीम के साथ रहिए।", "Madad aapke paas hai. Team ke saath rahiye.")
                    else s("The ambulance is still coming. To cancel it, tap I'm OK.", "एम्बुलेंस अभी आ रही है। रद्द करने के लिए 'मैं ठीक हूँ' दबाइए।",
                        "Ambulance abhi aa rahi hai. Cancel karne ke liye 'main theek hoon' dabaiye."), style)
                }
                false
            }
        }
    }

    /**
     * The screen left by some other route (another screen opened, the app closed). A countdown nobody watches must not
     * blare or dispatch on its own, so it is cancelled; a coming ambulance keeps being tracked and its next update
     * brings the screen back.
     */
    fun screenLeft() {
        screenShown = false
        val ui = _ui.value
        when (ui.phase) {
            EmergencyPhase.COUNTDOWN -> imOk(offerDoctor = false)
            EmergencyPhase.CANCELLED -> if (!ui.handedOver) close()
            EmergencyPhase.NO_UNITS -> close()
            else -> Unit
        }
    }

    fun call108(context: Context) {
        // Calling 108 replaces the countdown: no ambulance is auto-dispatched behind the call, and coming back from the
        // dialer shows "Cancelled" (with its own "Call 108") instead of a frozen countdown.
        val stopCountdown = _ui.value.phase == EmergencyPhase.COUNTDOWN && synchronized(lock) { (!dispatched).also { if (it) cancelled = true } }
        if (stopCountdown) {
            countdown?.cancel()
            intro?.cancel()
            listener?.cancel()
            _ui.update { it.copy(phase = EmergencyPhase.CANCELLED) }
            session?.let { id -> c.scope.launch { runCatching { client.emergencyCancel(id) } } }
        }
        siren.stop()
        // No dialer (Wi-Fi tablet, dialer disabled): never crash the emergency screen.
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun close() {
        countdown?.cancel()
        intro?.cancel()
        listener?.cancel()
        tracker?.cancel()
        dispatchJob?.cancel()
        handover?.cancel()
        siren.stop()
    }

    /** "Plot 12, Sector 5, Rohini, Delhi, 110085, India" → "Rohini": the locality, before city, PIN and country. */
    private fun shortArea(address: String): String? {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() || ch == ' ' } }
        return (parts.getOrNull(parts.size - 3) ?: parts.firstOrNull())?.take(28)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val DEFAULT_SEC = 15
        /** An outright request with a rough location: just long enough to type a landmark. */
        const val LANDMARK_SEC = 10
        const val CANCELLED_SHOW_MS = 2_500L
        const val HANDOVER_MS = 400L
    }
}

/** What someone says during the countdown. A negation ("not OK", "don't cancel") never cancels. */
object CountdownWords {
    enum class Said { OK, SEND, NOTHING }

    private val NOT_OK = listOf("not ok", "not okay", "not fine", "theek nahi", "thik nahi", "ठीक नहीं", "cancel mat", "mat cancel",
        "don't cancel", "dont cancel", "कैंसल मत", "mat roko", "roko mat", "rokna mat", "mat rokna", "मत रोको", "रोको मत",
        "मत रोकिए", "rokiye mat", "mat rokiye", "don't stop", "dont stop", "do not stop", "do not cancel")
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
