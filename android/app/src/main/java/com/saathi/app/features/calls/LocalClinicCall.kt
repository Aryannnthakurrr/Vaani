package com.saathi.app.features.calls

import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.ClinicCallRequest
import com.saathi.app.core.net.ClinicCallTurn
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device version of the backend's scripted clinic call (backend `app/llm/clinic_call.py`), used by the fake
 * backend: introduce + confirm → ask waiting time → thank and hang up. Pure, so it is unit-tested.
 */
object LocalClinicCall {
    private val YES = Regex("(?i)\\b(yes|yeah|confirmed?|correct|right|haan|ha|ji|theek|sahi|bilkul)\\b|हाँ|जी|ठीक")
    private val NO = Regex("(?i)\\b(no|not|cancel+ed|nahi|nahin|galat|wrong)\\b|नहीं")
    private val MINUTES = Regex("(?i)(\\d{1,3})\\s*(min|mins|minute|minutes|मिनट)")
    private val HOURS = Regex("(?i)(\\d{1,2}|an?|one|ek)\\s*(hour|hours|hr|ghanta|ghante|घंटा|घंटे)")

    /** Spoken Hindi fractions of an hour ("aadha ghanta" = 30 min), as receptionists say them. */
    private val FRACTIONS = listOf(
        Regex("(?i)half an hour|\\baa?dhe?a?\\b|आधा|आधे") to 30,
        Regex("(?i)\\bpaune?a?\\b|पौना") to 45,
        Regex("(?i)\\bsa[vw]a\\b|सवा") to 75,
        Regex("(?i)\\bdedh\\b|डेढ़") to 90,
        Regex("(?i)\\bdhaa?i\\b|ढाई") to 150,
    )

    fun waitingMinutes(text: String): Int? {
        MINUTES.find(text)?.let { return it.groupValues[1].toInt() }
        FRACTIONS.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.let { return it.second }
        HOURS.find(text)?.let { m -> return 60 * (m.groupValues[1].toIntOrNull() ?: 1) }
        return null
    }

    fun whenText(a: Appointment): String = a.startIso?.let { iso ->
        runCatching { LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofPattern("d MMMM 'at' h:mm a", Locale.ENGLISH)) }
            .getOrDefault(iso)
    } ?: "today"

    private const val ASK_WAIT = "Thank you. How long is the waiting time at the clinic right now?"

    fun turn(req: ClinicCallRequest): ClinicCallTurn {
        val agentTurns = req.transcript.count { it.role == "agent" }
        val clinicLines = req.transcript.filter { it.role == "clinic" }.map { it.text }
        val clinicSaid = clinicLines.joinToString(" ")
        val lastClinic = clinicLines.lastOrNull().orEmpty()
        val doctor = req.appointment.doctor ?: "the doctor"
        if (agentTurns == 0) {
            return ClinicCallTurn("Namaste. I am Vaani, an AI assistant calling on behalf of ${req.patientName}. " +
                "I would like to confirm the appointment with $doctor on ${whenText(req.appointment)}. Is that confirmed?")
        }
        // The first clear yes or no decides; later lines answer the waiting-time question ("no, 20 minutes").
        val confirmed = clinicLines.firstNotNullOfOrNull { answer(it) }
        if (confirmed == false) {
            return ClinicCallTurn("I understand. I will ask the patient to call you directly. Thank you, goodbye.",
                done = true, confirmed = false, summary = summary(req, false, null))
        }
        if (confirmed == null) {
            // Never report "confirmed" without a yes: ask once more, then leave it to the patient.
            if (agentTurns < 2) return ClinicCallTurn("Sorry, I did not catch that. Is the appointment confirmed, yes or no?")
            return ClinicCallTurn("Thank you. I will ask the patient to call you directly. Goodbye.",
                done = true, summary = summary(req, false, null))
        }
        val wait = waitingMinutes(lastClinic) ?: waitingMinutes(clinicSaid)
        if (wait == null && req.transcript.none { it.role == "agent" && it.text == ASK_WAIT }) return ClinicCallTurn(ASK_WAIT, confirmed = true)
        return ClinicCallTurn("Thank you very much for your help. Goodbye.", done = true, confirmed = true,
            waitingMinutes = wait, summary = summary(req, true, wait))
    }

    /** Yes, no, or unclear (neither, or both: "nahi ji, kal hai"). */
    private fun answer(text: String): Boolean? {
        val yes = YES.containsMatchIn(text)
        val no = NO.containsMatchIn(text)
        return if (yes == no) null else yes
    }

    private fun summary(req: ClinicCallRequest, confirmed: Boolean, wait: Int?): String {
        val doctor = req.appointment.doctor ?: "the doctor"
        if (!confirmed) return when (req.replyStyle) {
            "hi" -> "क्लिनिक ने $doctor के साथ आपकी मुलाक़ात पक्की नहीं की। कृपया खुद फ़ोन कर लीजिए।"
            "hinglish" -> "Clinic ne $doctor ke saath appointment confirm nahi kiya. Please khud phone kar lijiye."
            else -> "The clinic could not confirm your appointment with $doctor. Please call them yourself."
        }
        return when (req.replyStyle) {
            "hi" -> "मैंने क्लिनिक को फ़ोन किया। $doctor के साथ आपकी मुलाक़ात पक्की है।" + (wait?.let { " अभी लगभग $it मिनट इंतज़ार है।" } ?: "")
            "hinglish" -> "Maine clinic ko phone kiya. $doctor ke saath appointment confirm hai." + (wait?.let { " Abhi lagbhag $it minute ka wait hai." } ?: "")
            else -> "I called the clinic. Your appointment with $doctor on ${whenText(req.appointment)} is confirmed." +
                (wait?.let { " The waiting time is about $it minutes." } ?: "")
        }
    }
}
