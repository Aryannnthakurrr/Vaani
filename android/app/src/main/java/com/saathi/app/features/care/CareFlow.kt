package com.saathi.app.features.care

import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.Answer
import com.saathi.app.core.voice.SpokenAnswer
import com.saathi.app.core.voice.YesNo
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.DeviceLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
        val fix = DeviceLocation.lastKnown(c.context)
        val location = fix?.let {
            CareLocation(it.at.lat, it.at.lng, it.accuracyM?.toDouble(), DeviceLocation.address(c.context, it.at))
        }
        val patient = CarePatient(name = c.settings.current.userName.ifBlank { null })
        val session = client.start(CareStart(location, patient, style.wire))
        sessionId = session
        _ui.update { it.copy(phase = CarePhase.INTAKE) }
        say(s("Tell me what is wrong, for you or for someone near you. I am not a doctor, but I will help you find care.",
            "बताइए क्या तकलीफ़ है, आपको या आपके पास किसी को। मैं डॉक्टर नहीं हूँ, पर इलाज ढूँढने में मदद करूँगा।",
            "Bataiye kya takleef hai, aapko ya aapke paas kisi ko. Main doctor nahi hoon, par ilaaj dhoondhne mein madad karunga."))
        var answer = listen() ?: return
        while (true) {
            _ui.update { it.copy(phase = CarePhase.THINKING) }
            val reply = withSlowCue { client.intake(session, answer) }
            if (reply.done) {
                val note = reply.note ?: return fail(s("I could not finish the assessment.", "मैं आकलन पूरा नहीं कर पाया।", "Main assessment poora nahi kar paya."))
                _ui.update { it.copy(note = note, route = reply.route) }
                if (reply.route == "A") return c.emergency.start(session, note, location)
                return pathB(session, note)
            }
            _ui.update { it.copy(phase = CarePhase.INTAKE) }
            say(reply.question.orEmpty())
            answer = listen() ?: return
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
        client.search(session)
        _ui.update { it.copy(phase = CarePhase.SEARCHING) }
        var announcedCalls = false
        while (true) {
            val ui = poll(session)
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

    private suspend fun book(session: String, hospitalId: String, slot: Slot) {
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
                    b.callYourself?.let { phone ->
                        return say(s("I could not book it. Please call the hospital yourself at $phone.", "मैं बुक नहीं कर पाया। कृपया अस्पताल को $phone पर खुद फ़ोन कीजिए।",
                            "Main book nahi kar paya. Please hospital ko $phone par khud call kijiye."))
                    }
                    say(s("The call did not go through. I will try once more.", "फ़ोन नहीं लगा। मैं एक बार और कोशिश करता हूँ।", "Call nahi laga. Main ek baar aur try karta hoon."))
                    return book(session, hospitalId, slot)
                }
            }
        }
    }

    private var patient: CarePatient? = null

    /** Name, age and phone for the booking: from the profile, or asked once per session. */
    private suspend fun patientDetails(): CarePatient {
        patient?.let { return it }
        val name = c.settings.current.userName.ifBlank { null } ?: run {
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
        val whenText = CareText.time(b.startIso ?: cal?.startIso.orEmpty(), style)
        val ref = b.bookingRef?.let { s(", reference $it", ", रेफ़रेंस $it", ", reference $it") }.orEmpty()
        say(s("Booked: ${b.doctorName ?: cal?.title ?: "the appointment"}, $whenText$ref." + if (added) " I added it to your calendar." else "",
            "बुक हो गया: ${b.doctorName ?: cal?.title ?: "अपॉइंटमेंट"}, $whenText$ref।" + if (added) " मैंने इसे आपके कैलेंडर में जोड़ दिया।" else "",
            "Book ho gaya: ${b.doctorName ?: cal?.title ?: "appointment"}, $whenText$ref." + if (added) " Maine ise aapke calendar mein jod diya." else ""))
    }

    private companion object {
        const val POLL_MS = 1_500L
        const val SLOW_MS = 4_000L
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
