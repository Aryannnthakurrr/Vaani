package com.saathi.app.features.calls

import com.saathi.app.ui.theme.Touch
import com.saathi.app.ui.theme.Space
import com.saathi.app.ui.theme.OnFill
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.saathi.app.features.appointments.AppointmentState
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.InfoCard
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Accent
import com.saathi.app.ui.theme.AccentDark
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Practice calls only, so a practice works before anything is booked. Never offered for a real call. */
private val PRACTICE_APPOINTMENT = Appointment(doctor = "Dr. Anil Mehta", clinic = "Mehta Heart Clinic",
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
    // Only a booked appointment that is still ahead can be called about; a past one never reaches the clinic's phone.
    val upcoming = remember(settings.lastAppointment) {
        settings.lastAppointment?.let { runCatching { SaathiJson.decodeFromString(Appointment.serializer(), it) }.getOrNull() }
            ?.let { AppointmentState.upcoming(it, ZonedDateTime.now()) }
    }
    val lines = remember { mutableStateListOf<CallLine>() }
    var mode by remember { mutableStateOf(CallMode.CHOOSE) }
    var callAppt by remember { mutableStateOf<Appointment?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(0) }
    var realId by remember { mutableStateOf<String?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    /** The call being placed, in the app's scope: it outlives this screen, so a call placed after End or Back is hung up. */
    var placing by remember { mutableStateOf<Deferred<Result<String>>?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val realCalls = remember { RealCallClient { c.settings.current } }
    fun hangUpWhenPlaced(pending: Deferred<Result<String>>?) {
        pending ?: return
        c.scope.launch { pending.await().getOrNull()?.let { id: String -> runCatching { realCalls.hangUp(id) } } }
    }
    val patient = settings.userName.ifBlank { "the patient" }
    val shown = callAppt ?: upcoming

    suspend fun finish(s: String?, how: String) {
        if (summary != null) return // once, however the call ended (poll, End call, back)
        val text = s ?: tr(style, "The call ended.", "कॉल खत्म हुई।", "Call khatam hui.")
        summary = text
        logCall(c, callAppt, text, how)
        c.speaker.say(text, style)
    }

    fun practiceTurn() {
        val a = callAppt ?: return
        busy = true
        scope.launch {
            val req = ClinicCallRequest(patient, a, style.wire, lines.toList())
            val turn = runCatching { c.backend.clinicCallTurn(req) }.getOrElse { LocalClinicCall.turn(req) }
            lines += CallLine("agent", turn.say)
            c.speaker.say(turn.say, ReplyStyle.EN)
            if (turn.done) finish(turn.summary, "practice")
            busy = false
        }
    }

    fun startReal() {
        val a = upcoming ?: return
        callAppt = a
        error = null
        lines.clear()
        seconds = 0
        mode = CallMode.REAL
        status = tr(style, "Dialling the clinic…", "क्लिनिक को फ़ोन लगा रहे हैं…", "Clinic ko phone laga rahe hain…")
        val pending = c.scope.async { runCatching { realCalls.start(RealCallStart(patient, a, style.wire)).id } }
        placing = pending
        pollJob = scope.launch {
            val started = pending.await()
            started.onFailure { e ->
                // Setup problems (Bolna not configured, number not allowed) are for the family/developer, not the user.
                val setup = e.message.orEmpty().let { "BOLNA" in it || "ALLOWED" in it }
                error = (if (setup) {
                    tr(style, "Phone calls are not set up yet. Ask your family to finish setup, or try a practice call.",
                        "फ़ोन कॉल अभी सेट नहीं हुई है। परिवार से सेटअप पूरा करवाइए, या अभ्यास कॉल आज़माइए।",
                        "Phone call abhi set nahi hui hai. Family se setup poora karwaiye, ya practice call try kijiye.")
                } else {
                    tr(style, "Vaani could not place the call. Please check the internet and try again.",
                        "वाणी फ़ोन नहीं लगा पाया। इंटरनेट देखकर फिर से कोशिश कीजिए।",
                        "Vaani phone nahi laga paaya. Internet check karke phir se try kijiye.")
                }) + (if (com.saathi.app.BuildConfig.DEBUG) "\n(${e.message})" else "")
                mode = CallMode.CHOOSE
                return@launch
            }
            val id = started.getOrThrow()
            realId = id
            // Poll until the backend says the call is over; the transcript grows as the clinic speaks.
            var misses = 0
            while (isActive) {
                val state = runCatching { realCalls.poll(id) }.getOrNull()
                if (state != null) {
                    misses = 0
                    if (state.transcript.size > lines.size) lines += state.transcript.drop(lines.size)
                    status = statusText(state.status, style)
                    if (state.done) { finish(state.summary ?: endedText(state.status, style), "real"); break }
                } else if (++misses >= 20) {
                    // ~30 s without the server: hang up rather than leave a call running that the user can't follow.
                    runCatching { realCalls.hangUp(id) }
                    finish(tr(style, "Vaani lost touch with the call and hung up. Please try again later.",
                        "वाणी का कॉल से संपर्क टूट गया, इसलिए कॉल काट दी। बाद में फिर से कोशिश कीजिए।",
                        "Vaani ka call se connection toot gaya, isliye call kaat di. Baad mein phir se try kijiye."), "real")
                    break
                }
                delay(1500)
            }
        }
    }

    /** Hangs up once: the button shows "Ending…" meanwhile, and the poll loop stops so the summary is said once. */
    fun endReal() {
        if (ending || summary != null) return
        ending = true
        pollJob?.cancel()
        scope.launch {
            val id = realId
            if (id == null) {
                hangUpWhenPlaced(placing) // it may still connect: hang it up the moment it does
                finish(tr(style, "The call was stopped before it connected.", "कॉल जुड़ने से पहले ही रोक दी गई।",
                    "Call judne se pehle hi rok di gayi."), "real")
                return@launch
            }
            val state = runCatching { realCalls.hangUp(id) }.getOrNull()
            finish(when {
                state == null -> tr(style, "Vaani could not reach the server to hang up. The call will end by itself soon.",
                    "वाणी कॉल काटने के लिए सर्वर तक नहीं पहुँच पाया। कॉल थोड़ी देर में अपने-आप कट जाएगी।",
                    "Vaani call kaatne ke liye server tak nahi pahunch paaya. Call thodi der mein apne-aap kat jayegi.")
                else -> state.summary
            }, "real")
        }
    }

    // Back during a real call asks first; leaving the screen any other way still hangs up the real call.
    BackHandler(enabled = mode == CallMode.REAL && summary == null) { confirmLeave = true }
    DisposableEffect(Unit) {
        onDispose {
            val id = realId
            if (mode == CallMode.REAL && summary == null && id == null) hangUpWhenPlaced(placing)
            if (mode == CallMode.REAL && summary == null && id != null) {
                val a = callAppt
                c.scope.launch {
                    val state = runCatching { realCalls.hangUp(id) }.getOrNull()
                    logCall(c, a, state?.summary ?: "Hung up when the call screen was closed.", "real")
                }
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(tr(style, "End the call?", "कॉल काटें?", "Call kaatein?")) },
            text = { Text(tr(style, "Going back will hang up the call to the clinic.", "वापस जाने पर क्लिनिक की कॉल कट जाएगी।",
                "Wapas jaane par clinic ki call kat jayegi."), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ confirmLeave = false; endReal() }) { Text(tr(style, "End call", "कॉल काटें", "Call kaatein")) } },
            dismissButton = { TextButton({ confirmLeave = false }) { Text(tr(style, "Stay on the call", "कॉल पर रहें", "Call par rahein")) } },
        )
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
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(AccentDark, Accent)))
                .statusBarsPadding().padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text("📞", fontSize = 44.sp)
            Text(shown?.clinic ?: tr(style, "Clinic", "क्लिनिक", "Clinic"), style = MaterialTheme.typography.headlineMedium,
                color = OnFill, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val sub = when {
                mode == CallMode.CHOOSE -> tr(style, "Vaani can call the clinic for you", "वाणी आपके लिए क्लिनिक को फ़ोन कर सकता है",
                    "Vaani aapke liye clinic ko phone kar sakta hai")
                summary != null -> tr(style, "Call ended", "कॉल खत्म", "Call khatam")
                mode == CallMode.REAL -> "$status · %d:%02d".format(seconds / 60, seconds % 60)
                else -> tr(style, "Practice call · %d:%02d", "अभ्यास कॉल · %d:%02d", "Practice call · %d:%02d").format(seconds / 60, seconds % 60)
            }
            Text(sub, style = MaterialTheme.typography.titleMedium, color = OnFill.copy(alpha = 0.92f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.lg), state = listState,
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            item { Box(Modifier.padding(top = Space.sm)) }
            if (mode == CallMode.CHOOSE) {
                item {
                    if (upcoming != null) {
                        InfoCard(title = listOfNotNull(upcoming.doctor, upcoming.clinic).joinToString(" · "),
                            body = tr(style, "Vaani will confirm your appointment and ask how long the wait is.",
                                "वाणी आपकी मुलाक़ात पक्की करेगा और पूछेगा कि कितना इंतज़ार है।",
                                "Vaani appointment confirm karega aur poochega ki kitna wait hai."), icon = "🩺", accent = Leaf)
                    } else {
                        InfoCard(title = tr(style, "No upcoming appointment", "कोई आने वाली मुलाक़ात नहीं", "Koi aane wali appointment nahi"),
                            body = tr(style, "When you book a doctor, Vaani can call the clinic to confirm it. You can still try a practice call.",
                                "डॉक्टर से मुलाक़ात बुक होने पर वाणी क्लिनिक को फ़ोन करके उसे पक्का कर सकता है। अभ्यास कॉल अभी भी आज़मा सकते हैं।",
                                "Doctor ki appointment book hone par Vaani clinic ko phone karke confirm kar sakta hai. Practice call abhi bhi try kar sakte hain."),
                            icon = "📅")
                    }
                }
                error?.let { e -> item { InfoCard(title = tr(style, "Real call not available", "असली कॉल अभी नहीं हो सकती", "Real call abhi nahi ho sakti"),
                    body = e, icon = "⚠️", accent = Danger) } }
            }
            items(lines) { line -> Bubble(line, style) }
            // The first practice turn takes a moment to come back: show that Saathi is on it, not a blank screen.
            if (mode == CallMode.PRACTICE && busy && summary == null && lines.lastOrNull()?.role != "agent") {
                item { InfoCard(title = tr(style, "Vaani is speaking…", "वाणी बोल रहा है…", "Vaani bol raha hai…"), icon = "⏳") }
            }
            if (mode == CallMode.REAL && lines.isEmpty() && summary == null) {
                item { InfoCard(title = tr(style, "Waiting for the clinic", "क्लिनिक का इंतज़ार", "Clinic ka intezaar"),
                    body = tr(style, "The clinic's phone is ringing. Their words will appear here.",
                    "क्लिनिक का फ़ोन बज रहा है। उनकी बात यहाँ दिखेगी।", "Clinic ka phone baj raha hai. Unki baat yahan dikhegi."), icon = "📳") }
            }
            summary?.let { s -> item { InfoCard(title = tr(style, "Call summary", "कॉल का सार", "Call summary"), body = s, icon = "📋", accent = Leaf) } }
        }

        Surface(shadowElevation = 10.dp) {
            Column(Modifier.navigationBarsPadding().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                when {
                    summary != null -> BigButton(tr(style, "Done", "हो गया", "Ho gaya"), onBack)
                    mode == CallMode.CHOOSE -> {
                        if (upcoming != null) {
                            BigButton(tr(style, "📞 Call the clinic now", "📞 अभी क्लिनिक को फ़ोन करो", "📞 Abhi clinic ko phone karo"),
                                { startReal() }, color = Leaf)
                        }
                        BigButton(tr(style, "🎭 Practice call (type the clinic's side)", "🎭 अभ्यास कॉल (क्लिनिक की बात लिखें)",
                            "🎭 Practice call (clinic ki baat likhein)"), {
                                callAppt = upcoming ?: PRACTICE_APPOINTMENT
                                error = null
                                seconds = 0
                                mode = CallMode.PRACTICE
                                practiceTurn()
                            },
                            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Symptom-to-care: describe symptoms, then Saathi finds and calls hospitals (or sends help).
                        BigButton(tr(style, "🩺 Not feeling well? Find care", "🩺 तबीयत ठीक नहीं? इलाज ढूँढें", "🩺 Tabiyat theek nahi? Ilaaj dhoondhein"),
                            { c.care.start(); c.assistant.openScreen("care") }, color = Danger)
                    }
                    mode == CallMode.REAL -> BigButton(
                        if (ending) tr(style, "Ending the call…", "कॉल काट रहे हैं…", "Call kaat rahe hain…") else tr(style, "End call", "कॉल काटें", "Call kaatein"),
                        ::endReal, color = Danger, enabled = !ending)
                    else -> {
                        OutlinedTextField(
                            value = reply, onValueChange = { reply = it }, enabled = !busy, singleLine = true,
                            label = { Text(tr(style, "Clinic says…", "क्लिनिक कहता है…", "Clinic kehta hai…")) },
                            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primary),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            Button(onClick = { if (reply.isNotBlank()) { lines += CallLine("clinic", reply.trim()); reply = ""; practiceTurn() } },
                                enabled = !busy && reply.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = Touch.primary),
                                shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
                                Text(tr(style, "Send", "भेजें", "Bhejein"), style = MaterialTheme.typography.titleLarge)
                            }
                            Button(onClick = { c.speaker.stop(); onBack() }, modifier = Modifier.weight(1f).heightIn(min = Touch.primary),
                                shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                                Text(tr(style, "End call", "कॉल काटें", "Call kaatein"), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Writes the call to the activity log; a practice call is logged as practice, never as a real confirmation. */
private suspend fun logCall(c: AppContainer, appt: Appointment?, text: String, how: String) {
    val clinic = appt?.clinic ?: "the clinic"
    val plain = if (how == "practice") "Practice call to $clinic: $text" else "Called $clinic: $text"
    c.db.actionLog().insert(ActionLogEntity(ts = System.currentTimeMillis(), goal = "Call ${appt?.clinic ?: "clinic"}",
        actionJson = "{\"action\":\"clinic_call\",\"mode\":\"$how\"}", reason = null, pkg = "com.saathi.app",
        plain = plain, outcome = "done"))
}

/** Said when a real call ends without a summary from the clinic agent (busy line, nobody answered, failed). */
private fun endedText(status: String, style: ReplyStyle): String = when (status) {
    "busy" -> tr(style, "The clinic's line was busy. Please try again in a little while.", "क्लिनिक की लाइन व्यस्त थी। थोड़ी देर बाद फिर कोशिश कीजिए।",
        "Clinic ki line busy thi. Thodi der baad phir try kijiye.")
    "no-answer" -> tr(style, "Nobody answered at the clinic. Please try again later.", "क्लिनिक पर किसी ने फ़ोन नहीं उठाया। बाद में फिर कोशिश कीजिए।",
        "Clinic par kisi ne phone nahi uthaya. Baad mein phir try kijiye.")
    "failed", "error", "canceled", "cancelled" -> tr(style, "The call could not be completed. Please try again later.",
        "कॉल पूरी नहीं हो पाई। बाद में फिर कोशिश कीजिए।", "Call poori nahi ho paayi. Baad mein phir try kijiye.")
    else -> tr(style, "The call ended.", "कॉल खत्म हुई।", "Call khatam hui.")
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
private fun Bubble(line: CallLine, style: ReplyStyle) {
    val agent = line.role == "agent"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (agent) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(MaterialTheme.shapes.medium)
                .background(if (agent) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                .padding(Space.lg).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(if (agent) tr(style, "🤖 Vaani", "🤖 वाणी", "🤖 Vaani") else tr(style, "🏥 Clinic", "🏥 क्लिनिक", "🏥 Clinic"),
                style = MaterialTheme.typography.labelLarge, color = if (agent) AccentDark else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
