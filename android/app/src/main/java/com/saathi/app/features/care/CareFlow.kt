package com.saathi.app.features.care

import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.Answer
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.LiveVoiceSession
import com.saathi.app.core.voice.SpokenAnswer
import com.saathi.app.core.voice.YesNo
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.DeviceLocation
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The symptom-to-care conversation on the phone (docs/CARE.md). Everything is spoken and shown; every question takes
 * a spoken answer, a typed one, or a button tap, whichever comes first. Path A (emergency) hands over to
 * [EmergencyFlow]; Path B runs here: hospitals → calls → "Should I book?" → booking call → calendar.
 */
class CareFlow(private val c: AppContainer) {
    private val client = CareClient { c.settings.current }
    private val _ui = MutableStateFlow(CareUi())
    val ui: StateFlow<CareUi> = _ui
    private var job: Job? = null
    @Volatile var sessionId: String? = null
        private set

    private val style get() = c.settings.current.replyStyle
    private fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    // Answers that arrive from the screen instead of the microphone.
    @Volatile private var typed: CompletableDeferred<String>? = null
    @Volatile private var tapped: CompletableDeferred<Boolean>? = null
    @Volatile private var choice: CompletableDeferred<Pair<String, Slot>>? = null

    /** What the conversation is waiting for, so the screen can show the answer box or the Yes/No buttons. */
    enum class Asking { NOTHING, TEXT, YES_NO }
    private val _asking = MutableStateFlow(Asking.NOTHING)
    val asking: StateFlow<Asking> = _asking

    fun offerText(text: String) { typed?.complete(text.trim()) }
    fun tap(yes: Boolean) { tapped?.complete(yes) }
    fun choose(hospitalId: String, slot: Slot) { choice?.complete(hospitalId to slot) }

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        _ui.value = CareUi()
        job = c.scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: s("Something went wrong.", "कुछ गड़बड़ हो गई।", "Kuch gadbad ho gayi."))
            }
        }
    }

    fun stop() {
        job?.cancel()
        c.speaker.stop()
    }

    /** After "I'm OK" on the emergency screen: "Shall I find you a doctor instead?" then Path B, marked urgent. */
    fun offerDoctor(session: String, note: IntakeNote) {
        job?.cancel()
        _ui.update { it.copy(phase = CarePhase.INTAKE, note = note, route = "B") }
        job = c.scope.launch {
            c.assistant.openScreen("care")
            say(s("Okay. Shall I find you a doctor instead?", "ठीक है। क्या मैं आपके लिए डॉक्टर ढूँढूँ?", "Theek hai. Kya main aapke liye doctor dhoondhun?"))
            if (yesNo()) pathB(session, note, askFirst = false)
        }
    }

    private suspend fun fail(message: String) {
        _ui.update { it.copy(phase = CarePhase.ERROR, message = message) }
        say(message)
    }

    private suspend fun say(text: String, fromUser: Boolean = false) {
        _ui.update { it.copy(chat = it.chat + ChatLine(fromUser, text)) }
        if (!fromUser) c.speaker.say(text, style)
    }

    // ------------------------------------------------------------------------------------------- intake

    private suspend fun run() {
        _ui.update { it.copy(phase = CarePhase.STARTING) }
        val fix = DeviceLocation.current(c.context) // fresh: an ambulance may be sent to it
        val location = fix?.let {
            CareLocation(it.at.lat, it.at.lng, it.accuracyM?.toDouble(), DeviceLocation.address(c.context, it.at))
        }
        val profile = c.profiles.current()
        val patient = CarePatient(name = c.settings.current.userName.ifBlank { null }, age = profile?.age, sex = profile?.sex,
            conditions = profile?.conditions.orEmpty())
        val session = client.start(CareStart(location, patient, style.wire))
        sessionId = session
        _ui.update { it.copy(phase = CarePhase.INTAKE) }
        // Live first: a real-time conversation the user can interrupt. Turn-based only if Live can't start.
        when (val live = liveIntake(session)) {
            LiveIntake.Unavailable -> say(s("Tell me what is wrong, for you or for someone near you. I am not a doctor, but I will help you find care.",
                "बताइए क्या तकलीफ़ है, आपको या आपके पास किसी को। मैं डॉक्टर नहीं हूँ, पर इलाज ढूँढने में मदद करूँगा।",
                "Bataiye kya takleef hai, aapko ya aapke paas kisi ko. Main doctor nahi hoon, par ilaaj dhoondhne mein madad karunga."))
            // Dropped mid-way: carry on turn by turn; the session keeps everything said so far.
            LiveIntake.Ended -> say(s("The connection dropped. Let's continue. Please tell me again how you are feeling.",
                "कनेक्शन टूट गया। चलिए आगे बढ़ते हैं। कृपया फिर से बताइए कैसा महसूस हो रहा है।",
                "Connection toot gaya. Chaliye aage badhte hain. Please phir se bataiye kaisa mehsoos ho raha hai."))
            is LiveIntake.Done -> return finishIntake(session, live.reply, location)
        }
        var answer = listen() ?: return
        while (true) {
            _ui.update { it.copy(phase = CarePhase.THINKING) }
            val reply = withSlowCue { client.intake(session, answer) }
            if (reply.done) return finishIntake(session, reply, location)
            _ui.update { it.copy(phase = CarePhase.INTAKE) }
            say(reply.question.orEmpty())
            answer = listen() ?: return
        }
    }

    private suspend fun finishIntake(session: String, reply: IntakeReply, location: CareLocation?) {
        val note = reply.note ?: return fail(s("I could not finish the assessment.", "मैं आकलन पूरा नहीं कर पाया।", "Main assessment poora nahi kar paya."))
        _ui.update { it.copy(note = note, route = reply.route) }
        if (reply.route == "A") return c.emergency.start(session, note, location)
        pathB(session, note)
    }

    private sealed interface LiveIntake {
        data object Unavailable : LiveIntake // never connected (offline, mock backend): use the turn-based intake
        data object Ended : LiveIntake // the connection dropped before the intake finished
        data class Done(val reply: IntakeReply) : LiveIntake
    }

    /**
     * The intake as a Gemini Live conversation (`/live/voice?care=…`): the user can talk over Saathi at any time.
     * The backend's triage decides the questions and the outcome and hands the note over as the `care_done` tool.
     */
    private suspend fun liveIntake(session: String): LiveIntake = coroutineScope {
        val base = c.backend.liveVoiceUrl() ?: return@coroutineScope LiveIntake.Unavailable
        val result = CompletableDeferred<LiveIntake>()
        val live = LiveVoiceSession(c.context, "$base&care=$session", { style.wire },
            tools = { name, args ->
                if (name == "care_done") {
                    runCatching { SaathiJson.decodeFromJsonElement(IntakeReply.serializer(), args) }.getOrNull()
                        ?.let { result.complete(LiveIntake.Done(it)) }
                }
                buildJsonObject { put("ok", JsonPrimitive(true)) }
            },
            onEnded = { connected -> result.complete(if (connected) LiveIntake.Ended else LiveIntake.Unavailable) })
        val mirror = launch {
            live.transcripts.collect { lines ->
                _ui.update { ui -> ui.copy(chat = lines.takeLast(6).map { ChatLine(it.role == "user", it.text) }) }
            }
        }
        try {
            live.start()
            // The server reads the reply style first, so the opening cue goes only once the socket is open.
            if (live.state.first { it != LiveState.CONNECTING } == LiveState.LISTENING) live.sendText("[start intake]")
            val outcome = result.await()
            if (outcome is LiveIntake.Done) {
                // Let Saathi finish its closing sentence ("I am getting help now") before hanging up.
                withTimeoutOrNull(8_000) {
                    delay(800)
                    live.state.first { it != LiveState.SPEAKING }
                }
            }
            outcome
        } finally {
            mirror.cancel()
            live.end()
        }
    }

    /** Says "one moment" if the server takes longer than 4 s, so a slow model never sounds like a hang. */
    private suspend fun <T> withSlowCue(block: suspend () -> T): T = coroutineScope {
        var speaking = false
        val cue = launch {
            delay(SLOW_MS)
            speaking = true
            c.speaker.sayPhrase("filler", style)
        }
        try {
            block()
        } finally {
            if (speaking) cue.join() else cue.cancel()
        }
    }

    /** A spoken (after a beep) or typed answer; null after about a minute of silence. */
    private suspend fun listen(): String? = coroutineScope {
        val box = CompletableDeferred<String>().also { typed = it }
        _asking.value = Asking.TEXT
        val voice = async {
            SpokenAnswer.freeText(
                listen = { c.assistant.speech.listen(cue = true)?.transcript },
                reprompt = { c.speaker.sayPhrase("still_listening", style) },
            ) ?: awaitCancellation()
        }
        val heard = select { box.onAwait { it }; voice.onAwait { it } }
        voice.cancel()
        typed = null
        _asking.value = Asking.NOTHING
        _ui.update { it.copy(chat = it.chat + ChatLine(true, heard)) }
        heard
    }

    /** Yes/no by voice (beep, patient re-prompts) or the on-screen buttons. */
    private suspend fun yesNo(): Boolean = coroutineScope {
        val buttons = CompletableDeferred<Boolean>().also { tapped = it }
        val box = CompletableDeferred<String>().also { typed = it }
        _asking.value = Asking.YES_NO
        val voice = async {
            SpokenAnswer.yesNo(
                listen = { c.assistant.speech.listen(cue = true)?.transcript },
                reprompt = { c.speaker.sayPhrase("ask_yes_no_again", style) },
            ) ?: run { c.speaker.sayPhrase("waiting_for_tap", style); awaitCancellation() }
        }
        val text = async { when (YesNo.parse(box.await())) { Answer.YES -> true; Answer.NO -> false; Answer.UNCLEAR -> awaitCancellation() } }
        val answer = select { buttons.onAwait { it }; voice.onAwait { it }; text.onAwait { it } }
        voice.cancel(); text.cancel()
        tapped = null; typed = null
        _asking.value = Asking.NOTHING
        c.speaker.stop()
        answer
    }

    // ------------------------------------------------------------------------------------------- path B

    private suspend fun pathB(session: String, note: IntakeNote, askFirst: Boolean = true) {
        if (askFirst) {
            say(s("This does not look like an emergency. Shall I call three hospitals near you to ask about a ${note.specialty} doctor?",
                "यह इमरजेंसी नहीं लगती। क्या मैं पास के तीन अस्पतालों में ${note.specialty} डॉक्टर के बारे में फ़ोन करके पूछूँ?",
                "Yeh emergency nahi lagti. Kya main paas ke teen hospitals mein ${note.specialty} doctor ke baare mein call karke poochun?"))
            if (!yesNo()) return say(s("Okay. If it gets worse, call 108.", "ठीक है। अगर तबीयत बिगड़े तो 108 पर फ़ोन कीजिए।", "Theek hai. Agar tabiyat bigde to 108 par call kijiye."))
        }
        // Details first, so that if a hospital offers a slot while we are on the line, a yes books it on that call.
        say(s("So I can book while I'm on the phone with them, a few quick details.", "ताकि फ़ोन पर ही बुक कर सकूँ, बस कुछ जानकारी।",
            "Taaki phone par hi book kar sakun, bas kuch details."))
        client.search(session, patientDetails())
        _ui.update { it.copy(phase = CarePhase.SEARCHING) }
        var announcedCalls = false
        var askedLive: String? = null
        while (true) {
            val ui = poll(session)
            val ask = ui.liveAsk
            if (ask != null && ask.askId != askedLive) {
                askedLive = ask.askId
                askWhileOnTheCall(session, ask)
                continue
            }
            if (ui.liveUnclear) {
                _ui.update { it.copy(liveUnclear = false) }
                say(s("I couldn't confirm that booking on the call. I'll tell you what the hospitals said.",
                    "उस कॉल पर बुकिंग पक्की नहीं हो पाई। मैं बताता हूँ अस्पतालों ने क्या कहा।",
                    "Us call par booking pakki nahi ho payi. Main batata hoon hospitals ne kya kaha."))
            }
            ui.booking?.takeIf { it.status == "confirmed" }?.let { return booked(it) }
            if (!announcedCalls && ui.phase == CarePhase.CALLING) {
                announcedCalls = true
                val calling = ui.hospitals.filter { it.calling }.joinToString(", ") { it.hospital.name }
                say(s("I am calling $calling now. This takes a few minutes.", "मैं अभी $calling को फ़ोन कर रहा हूँ। इसमें कुछ मिनट लगेंगे।",
                    "Main abhi $calling ko call kar raha hoon. Isme kuch minute lagenge."))
            }
            when (ui.phase) {
                CarePhase.RECOMMEND -> return recommend(session, ui.recommendation ?: return)
                CarePhase.NO_OPTIONS, CarePhase.ERROR -> return say(ui.message ?: s("I could not find an appointment.", "मुझे अपॉइंटमेंट नहीं मिला।", "Mujhe appointment nahi mila."))
                else -> delay(POLL_MS)
            }
        }
    }

    /**
     * A hospital is holding the line with a concrete slot. One spoken or tapped yes books it on that call; silence or
     * a late answer leaves the call to say "we'll call back", and the usual recommendation follows.
     */
    private suspend fun askWhileOnTheCall(session: String, ask: LiveAsk) {
        val who = listOf(ask.doctor, ask.hospitalName).filter { it.isNotBlank() }.joinToString(", ")
        val fee = ask.fee.takeIf { it.isNotBlank() }?.let { s(", fee $it", ", फ़ीस $it", ", fee $it") }.orEmpty()
        say(s("I'm on the phone with ${ask.hospitalName} now. They can see you: $who, ${ask.slot}$fee. Shall I book it?",
            "मैं अभी ${ask.hospitalName} से फ़ोन पर बात कर रहा हूँ। $who, ${ask.slot}$fee मिल सकता है। क्या बुक कर दूँ?",
            "Main abhi ${ask.hospitalName} se phone par baat kar raha hoon. $who, ${ask.slot}$fee mil sakta hai. Kya book kar doon?"))
        val yes = withTimeoutOrNull(LIVE_ANSWER_MS) { yesNo() }
        if (yes == null) {
            tapped = null; typed = null  // yesNo() was cancelled before its own clean-up
            _asking.value = Asking.NOTHING
            c.speaker.stop()
            return say(s("I'll tell them we'll call back.", "मैं उन्हें बोल देता हूँ कि हम वापस फ़ोन करेंगे।", "Main unhe bol deta hoon ki hum wapas call karenge."))
        }
        val accepted = runCatching { client.liveConfirm(session, ask.askId, yes) }.getOrDefault(false)
        say(when {
            !accepted -> s("They couldn't wait, so I'll call them back.", "वे रुक नहीं पाए, मैं उन्हें वापस फ़ोन करूँगा।", "Woh ruk nahi paaye, main unhe wapas call karunga.")
            yes -> s("Booking it now, while they're on the line.", "अभी फ़ोन पर ही बुक कर रहा हूँ।", "Abhi phone par hi book kar raha hoon.")
            else -> s("Okay, not this one.", "ठीक है, यह नहीं।", "Theek hai, yeh nahi.")
        })
    }

    private suspend fun poll(session: String): CareUi {
        val events = runCatching { client.events(session, _ui.value.lastSeq) }.getOrDefault(emptyList())
        _ui.update { ui -> events.fold(ui) { acc, e -> CareReducer.apply(acc, e) } }
        return _ui.value
    }

    private suspend fun recommend(session: String, rec: Recommendation) {
        say(rec.spokenText)
        val picked = coroutineScope {
            val tap = CompletableDeferred<Pair<String, Slot>>().also { choice = it }
            val spoken = async { if (yesNo()) rec.primary.hospitalId to rec.slot else null }
            val result = select<Pair<String, Slot>?> { tap.onAwait { it }; spoken.onAwait { it } }
            spoken.cancel(); choice = null; tapped = null
            result
        } ?: return say(s("Okay, I won't book. The other options are on the screen.", "ठीक है, मैं बुक नहीं करूँगा। बाकी विकल्प स्क्रीन पर हैं।",
            "Theek hai, main book nahi karunga. Baaki options screen par hain."))
        book(session, picked.first, picked.second)
    }

    /** @param retried the booking call already failed once; a second failure gives the user the number instead. */
    private suspend fun book(session: String, hospitalId: String, slot: Slot, retried: Boolean = false) {
        val patient = patientDetails()
        client.book(session, BookRequest(hospitalId, slot, patient))
        _ui.update { it.copy(phase = CarePhase.BOOKING, booking = null) }
        say(s("I am calling the hospital to book it.", "मैं बुक करने के लिए अस्पताल को फ़ोन कर रहा हूँ।", "Main book karne ke liye hospital ko call kar raha hoon."))
        while (true) {
            delay(POLL_MS)
            val b = poll(session).booking?.takeIf { it.status != "calling" } ?: continue
            when (b.status) {
                "confirmed" -> return booked(b)
                "slot_unavailable" -> {
                    val alt = b.alternatives.firstOrNull()
                        ?: return say(s("That time is taken and they offered no other time.", "वह समय भर गया है और कोई दूसरा समय नहीं मिला।", "Woh time bhar gaya hai aur koi doosra time nahi mila."))
                    say(s("That time is taken. They offered ${CareText.time(alt.start, style)}. Shall I book that?",
                        "वह समय भर गया है। उन्होंने ${CareText.time(alt.start, style)} बताया है। क्या वह बुक करूँ?",
                        "Woh time bhar gaya hai. Unhone ${CareText.time(alt.start, style)} bataya hai. Kya woh book karun?"))
                    return if (yesNo()) book(session, hospitalId, alt) else say(s("Okay, I have not booked anything.", "ठीक है, मैंने कुछ बुक नहीं किया।", "Theek hai, maine kuch book nahi kiya."))
                }
                else -> {
                    val phone = b.callYourself ?: _ui.value.hospitals.firstOrNull { it.hospital.id == hospitalId }?.hospital?.phone
                    if (retried || b.callYourself != null) {
                        return say(phone?.let {
                            s("I could not book it. Please call the hospital yourself at $it.", "मैं बुक नहीं कर पाया। कृपया अस्पताल को $it पर खुद फ़ोन कीजिए।",
                                "Main book nahi kar paya. Please hospital ko $it par khud call kijiye.")
                        } ?: s("I could not book it. Please call the hospital yourself.", "मैं बुक नहीं कर पाया। कृपया अस्पताल को खुद फ़ोन कीजिए।",
                            "Main book nahi kar paya. Please hospital ko khud call kijiye."))
                    }
                    say(s("The call did not go through. I will try once more.", "फ़ोन नहीं लगा। मैं एक बार और कोशिश करता हूँ।", "Call nahi laga. Main ek baar aur try karta hoon."))
                    return book(session, hospitalId, slot, retried = true)
                }
            }
        }
    }

    private var patient: CarePatient? = null

    /** Name, age and phone for the booking: from the profile, or asked once per session. */
    private suspend fun patientDetails(): CarePatient {
        patient?.let { return it }
        // The profile name is the phone's owner; the patient may be a family member ("Papa is unwell").
        val own = c.settings.current.userName.ifBlank { null }
        val forSelf = own != null && run {
            say(s("Is this appointment for you, $own?", "क्या यह अपॉइंटमेंट आपके लिए है, $own?", "Kya yeh appointment aapke liye hai, $own?"))
            yesNo()
        }
        val name = if (forSelf) own else run {
            say(s("What is the patient's name?", "मरीज़ का नाम क्या है?", "Patient ka naam kya hai?"))
            listen()
        }
        say(s("And the patient's age?", "और मरीज़ की उम्र?", "Aur patient ki umar?"))
        val age = listen()?.let { Regex("\\d{1,3}").find(it)?.value?.toIntOrNull() }
        say(s("Which phone number should the hospital use?", "अस्पताल किस फ़ोन नंबर पर संपर्क करे?", "Hospital kis phone number par contact kare?"))
        val phone = listen()?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 }
        return CarePatient(name = name, age = age, phone = phone).also { patient = it }
    }

    private suspend fun booked(b: BookingStatus) {
        val cal = b.calendar
        val added = cal?.let { c.calendar.insertEvent(it.title, it.location, it.description, it.startIso, it.endIso) } != null
        // Shared with every other workflow: reminders, "cab to my appointment", and the assistant's context.
        val hospital = _ui.value.hospitals.firstOrNull { it.hospital.id == b.hospitalId }?.hospital
        val appt = com.saathi.app.core.net.Appointment(doctor = b.doctorName, clinic = hospital?.name,
            address = hospital?.address ?: cal?.location, phone = hospital?.phone, startIso = b.startIso ?: cal?.startIso)
        c.settings.setLastAppointment(com.saathi.app.util.SaathiJson.encodeToString(com.saathi.app.core.net.Appointment.serializer(), appt))
        com.saathi.app.features.inbox.ReminderPlanner.forAppointment(appt, java.time.ZonedDateTime.now())
            .forEach { c.reminders.add(c.db.reminders(), it) }
        val whenText = CareText.time(b.startIso ?: cal?.startIso.orEmpty(), style)
        val ref = b.bookingRef?.let { s(", reference $it", ", रेफ़रेंस $it", ", reference $it") }.orEmpty()
        say(s("Booked: ${b.doctorName ?: cal?.title ?: "the appointment"}, $whenText$ref." + if (added) " I added it to your calendar." else "",
            "बुक हो गया: ${b.doctorName ?: cal?.title ?: "अपॉइंटमेंट"}, $whenText$ref।" + if (added) " मैंने इसे आपके कैलेंडर में जोड़ दिया।" else "",
            "Book ho gaya: ${b.doctorName ?: cal?.title ?: "appointment"}, $whenText$ref." + if (added) " Maine ise aapke calendar mein jod diya." else ""))
    }

    private companion object {
        const val POLL_MS = 1_500L
        const val SLOW_MS = 4_000L
        /** A little under the backend's 25 s hold (care/live_confirm.py WAIT_S), so a yes still reaches the call. */
        const val LIVE_ANSWER_MS = 20_000L
    }
}

/** Times read aloud: "tomorrow 11:00 AM". */
object CareText {
    fun time(iso: String, style: ReplyStyle): String = runCatching {
        val t = java.time.OffsetDateTime.parse(iso)
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(t.offset), t.toLocalDate())
        val day = when (days) {
            0L -> tr(style, "today", "आज", "aaj")
            1L -> tr(style, "tomorrow", "कल", "kal")
            else -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
        }
        "$day ${t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))}"
    }.getOrDefault(iso)
}
