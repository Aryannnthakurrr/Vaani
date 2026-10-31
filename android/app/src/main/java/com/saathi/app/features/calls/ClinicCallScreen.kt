package com.saathi.app.features.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.ActionLogEntity
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.CallLine
import com.saathi.app.core.net.ClinicCallRequest
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.InfoCard
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Saffron
import com.saathi.app.ui.theme.SaffronDark
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Used when no appointment has been booked yet, so the demo always works. */
private val DEMO_APPOINTMENT = Appointment(doctor = "Dr. Anil Mehta", clinic = "Mehta Heart Clinic",
    startIso = java.time.LocalDate.now().atTime(16, 0).toString())

private enum class CallMode { CHOOSE, PRACTICE, REAL }

/**
 * AI call to the clinic (SPEC §7.7). **Real**: a Bolna voice agent phones the clinic for the backend and runs the
 * conversation; the app shows the live transcript and can hang up. **Practice**: a teammate types the clinic's
 * side. Either way the summary is spoken to the user in their language and saved in the activity log.
 */
@Composable
fun ClinicCallScreen(c: AppContainer, onBack: () -> Unit) {
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    val appt = remember(settings.lastAppointment) {
        settings.lastAppointment?.let { runCatching { SaathiJson.decodeFromString(Appointment.serializer(), it) }.getOrNull() }
            ?: DEMO_APPOINTMENT
    }
    val lines = remember { mutableStateListOf<CallLine>() }
    var mode by remember { mutableStateOf(CallMode.CHOOSE) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(0) }
    var realId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val realCalls = remember { RealCallClient { c.settings.current } }
    val patient = settings.userName.ifBlank { "the patient" }

    suspend fun finish(s: String?, how: String) {
        summary = s ?: tr(style, "The call ended.", "कॉल खत्म हुई।", "Call khatam hui.")
        c.db.actionLog().insert(ActionLogEntity(ts = System.currentTimeMillis(), goal = "Call ${appt.clinic ?: "clinic"}",
            actionJson = "{\"action\":\"clinic_call\",\"mode\":\"$how\"}", reason = null, pkg = "com.saathi.app",
            plain = "Called ${appt.clinic ?: "the clinic"}: ${summary!!}", outcome = "done"))
        c.speaker.say(summary!!, style)
    }

    fun practiceTurn() {
        busy = true
        scope.launch {
            val req = ClinicCallRequest(patient, appt, style.wire, lines.toList())
            val turn = runCatching { c.backend.clinicCallTurn(req) }.getOrElse { LocalClinicCall.turn(req) }
            lines += CallLine("agent", turn.say)
            c.speaker.say(turn.say, ReplyStyle.EN)
            if (turn.done) finish(turn.summary, "practice")
            busy = false
        }
    }

    fun startReal() {
        mode = CallMode.REAL
        status = tr(style, "Dialling the clinic…", "क्लिनिक को फ़ोन लगा रहे हैं…", "Clinic ko phone laga rahe hain…")
        scope.launch {
            val started = runCatching { realCalls.start(RealCallStart(patient, appt, style.wire)) }
            started.onFailure { e ->
                // Setup problems (Bolna not configured, number not allowed) are for the family/developer, not the user.
                val setup = e.message.orEmpty().let { "BOLNA" in it || "ALLOWED" in it }
                error = if (setup) {
                    tr(style, "Phone calls are not set up yet. Ask your family to finish setup, or try a practice call.",
                        "फ़ोन कॉल अभी सेट नहीं हुई है। परिवार से सेटअप पूरा करवाइए, या अभ्यास कॉल आज़माइए।",
                        "Phone call abhi set nahi hui hai. Family se setup poora karwaiye, ya practice call try kijiye.") +
                        (if (com.saathi.app.BuildConfig.DEBUG) "\n(${e.message})" else "")
                } else e.message
                mode = CallMode.CHOOSE
                return@launch
            }
            realId = started.getOrThrow().id
            // Poll until the backend says the call is over; the transcript grows as the clinic speaks.
            while (isActive) {
                val state = runCatching { realCalls.poll(realId!!) }.getOrNull()
                if (state != null) {
                    if (state.transcript.size > lines.size) lines += state.transcript.drop(lines.size)
                    status = statusText(state.status, style)
                    if (state.done) { finish(state.summary, "real"); break }
                }
                delay(1500)
            }
        }
    }

    LaunchedEffect(mode, summary) { while (mode != CallMode.CHOOSE && summary == null) { delay(1000); seconds++ } }
    LaunchedEffect(lines.size, summary) {
        delay(150) // let the new item lay out first
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Call header, like a phone's in-call screen.
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(SaffronDark, Saffron)))
                .statusBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("📞", fontSize = 44.sp)
            Text(appt.clinic ?: tr(style, "Clinic", "क्लिनिक", "Clinic"), style = MaterialTheme.typography.headlineMedium,
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val sub = when {
                mode == CallMode.CHOOSE -> tr(style, "Saathi can call the clinic for you", "साथी आपके लिए क्लिनिक को फ़ोन कर सकता है",
                    "Saathi aapke liye clinic ko phone kar sakta hai")
                summary != null -> tr(style, "Call ended", "कॉल खत्म", "Call khatam")
                mode == CallMode.REAL -> "$status · %d:%02d".format(seconds / 60, seconds % 60)
                else -> tr(style, "Practice call · %d:%02d", "अभ्यास कॉल · %d:%02d", "Practice call · %d:%02d").format(seconds / 60, seconds % 60)
            }
            Text(sub, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Box(Modifier.padding(top = 8.dp)) }
            if (mode == CallMode.CHOOSE) {
                item {
                    InfoCard(title = listOfNotNull(appt.doctor, appt.clinic).joinToString(" · "),
                        body = tr(style, "Saathi will confirm your appointment and ask how long the wait is.",
                            "साथी आपकी मुलाक़ात पक्की करेगा और पूछेगा कि कितना इंतज़ार है।",
                            "Saathi appointment confirm karega aur poochega ki kitna wait hai."), icon = "🩺", accent = Leaf)
                }
                error?.let { e -> item { InfoCard(title = tr(style, "Real call not available", "असली कॉल अभी नहीं हो सकती", "Real call abhi nahi ho sakti"),
                    body = e, icon = "⚠️", accent = Danger) } }
            }
            items(lines) { line -> Bubble(line) }
            if (mode == CallMode.REAL && lines.isEmpty() && summary == null) {
                item { InfoCard(title = status, body = tr(style, "The clinic's phone is ringing. Their words will appear here.",
                    "क्लिनिक का फ़ोन बज रहा है। उनकी बात यहाँ दिखेगी।", "Clinic ka phone baj raha hai. Unki baat yahan dikhegi."), icon = "📳") }
            }
            summary?.let { s -> item { InfoCard(title = tr(style, "Call summary", "कॉल का सार", "Call summary"), body = s, icon = "📋", accent = Leaf) } }
        }

        Surface(shadowElevation = 10.dp) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    summary != null -> BigButton(tr(style, "Done", "हो गया", "Ho gaya"), onBack)
                    mode == CallMode.CHOOSE -> {
                        BigButton(tr(style, "📞 Call the clinic now", "📞 अभी क्लिनिक को फ़ोन करो", "📞 Abhi clinic ko phone karo"),
                            { error = null; startReal() }, color = Leaf)
                        BigButton(tr(style, "🎭 Practice call (type the clinic's side)", "🎭 अभ्यास कॉल (क्लिनिक की बात लिखें)",
                            "🎭 Practice call (clinic ki baat likhein)"), { mode = CallMode.PRACTICE; practiceTurn() },
                            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Symptom-to-care: describe symptoms, then Saathi finds and calls hospitals (or sends help).
                        BigButton(tr(style, "🩺 Not feeling well? Find care", "🩺 तबीयत ठीक नहीं? इलाज ढूँढें", "🩺 Tabiyat theek nahi? Ilaaj dhoondhein"),
                            { c.care.start(); c.assistant.openScreen("care") }, color = Danger)
                    }
                    mode == CallMode.REAL -> BigButton(tr(style, "End call", "कॉल काटें", "Call kaatein"), {
                        scope.launch {
                            c.speaker.stop()
                            val id = realId ?: return@launch onBack()
                            runCatching { realCalls.hangUp(id) }.onSuccess { finish(it.summary, "real") }.onFailure { onBack() }
                        }
                    }, color = Danger)
                    else -> {
                        OutlinedTextField(
                            value = reply, onValueChange = { reply = it }, enabled = !busy, singleLine = true,
                            label = { Text(tr(style, "Clinic says…", "क्लिनिक कहता है…", "Clinic kehta hai…")) },
                            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { if (reply.isNotBlank()) { lines += CallLine("clinic", reply.trim()); reply = ""; practiceTurn() } },
                                enabled = !busy && reply.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                                shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
                                Text(tr(style, "Send", "भेजें", "Bhejein"), style = MaterialTheme.typography.titleLarge)
                            }
                            Button(onClick = { c.speaker.stop(); onBack() }, modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                                shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                                Text(tr(style, "End call", "कॉल काटें", "Call kaatein"), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Call statuses in plain words. */
private fun statusText(status: String, style: ReplyStyle): String = when (status) {
    "starting", "dialing", "queued", "initiated" -> tr(style, "Dialling…", "फ़ोन लगा रहे हैं…", "Phone laga rahe hain…")
    "ringing" -> tr(style, "Ringing…", "घंटी बज रही है…", "Ghanti baj rahi hai…")
    "in-progress", "answered" -> tr(style, "On the call", "कॉल पर", "Call par")
    "busy" -> tr(style, "Line busy", "लाइन व्यस्त", "Line busy")
    "no-answer" -> tr(style, "No answer", "किसी ने नहीं उठाया", "Kisi ne nahi uthaya")
    else -> tr(style, "Call ended", "कॉल खत्म", "Call khatam")
}

@Composable
private fun Bubble(line: CallLine) {
    val agent = line.role == "agent"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (agent) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp))
                .background(if (agent) Saffron.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(if (agent) "🤖 Saathi" else "🏥 Clinic", style = MaterialTheme.typography.labelLarge,
                color = if (agent) SaffronDark else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
