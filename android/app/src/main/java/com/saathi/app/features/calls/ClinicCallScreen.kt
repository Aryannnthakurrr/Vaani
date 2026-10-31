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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.ActionLogEntity
import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.CallLine
import com.saathi.app.core.net.ClinicCallRequest
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.InfoCard
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Saffron
import com.saathi.app.ui.theme.SaffronDark
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Used when no appointment has been booked yet, so the demo always works. */
private val DEMO_APPOINTMENT = Appointment(doctor = "Dr. Anil Mehta", clinic = "Mehta Heart Clinic",
    startIso = java.time.LocalDate.now().atTime(16, 0).toString())

/**
 * Simulated AI call to the clinic (SPEC §7.7 stretch). Saathi speaks its side out loud; a teammate types the
 * clinic's replies (no telephony account needed). The summary is spoken to the user and saved in the activity log.
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
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun nextTurn() {
        busy = true
        scope.launch {
            val req = ClinicCallRequest(settings.userName.ifBlank { "the patient" }, appt, style.wire, lines.toList())
            val turn = runCatching { c.backend.clinicCallTurn(req) }.getOrElse { LocalClinicCall.turn(req) }
            lines += CallLine("agent", turn.say)
            c.speaker.say(turn.say, com.saathi.app.core.net.ReplyStyle.EN)
            if (turn.done) {
                summary = turn.summary
                turn.summary?.let { s ->
                    c.db.actionLog().insert(ActionLogEntity(ts = System.currentTimeMillis(), goal = "Call ${appt.clinic ?: "clinic"}",
                        actionJson = "{\"action\":\"clinic_call\"}", reason = null, pkg = "com.saathi.app",
                        plain = "Called ${appt.clinic ?: "the clinic"}: $s", outcome = "done"))
                    c.speaker.say(s, style)
                }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { nextTurn() }
    LaunchedEffect(summary) { while (summary == null) { delay(1000); seconds++ } }
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
            Text(appt.clinic ?: tr(style, "Clinic", "क्लिनिक", "Clinic"), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                if (summary == null) tr(style, "Saathi is on the call · %d:%02d", "साथी कॉल पर है · %d:%02d", "Saathi call par hai · %d:%02d")
                    .format(seconds / 60, seconds % 60) else tr(style, "Call ended", "कॉल खत्म", "Call khatam"),
                style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f),
            )
            Text(tr(style, "Simulated call: a teammate types the clinic's replies below.", "नकली कॉल: क्लिनिक की तरफ़ से कोई साथी नीचे जवाब लिखे।",
                "Simulated call: clinic ki taraf se koi teammate neeche reply likhe."), style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Box(Modifier.padding(top = 8.dp)) }
            items(lines) { line -> Bubble(line) }
            summary?.let { s -> item { InfoCard(title = tr(style, "Call summary", "कॉल का सार", "Call summary"), body = s, icon = "📋", accent = Leaf) } }
        }

        Surface(shadowElevation = 10.dp) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (summary == null) {
                    OutlinedTextField(
                        value = reply, onValueChange = { reply = it }, enabled = !busy, singleLine = true,
                        label = { Text(tr(style, "Clinic says…", "क्लिनिक कहता है…", "Clinic kehta hai…")) },
                        textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (reply.isNotBlank()) { lines += CallLine("clinic", reply.trim()); reply = ""; nextTurn() } },
                            enabled = !busy && reply.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
                            Text(tr(style, "Send", "भेजें", "Bhejein"), style = MaterialTheme.typography.titleLarge)
                        }
                        Button(onClick = { c.speaker.stop(); onBack() }, modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                            shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                            Text(tr(style, "End call", "कॉल काटें", "Call kaatein"), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                } else {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp)) {
                        Text(tr(style, "Done", "हो गया", "Ho gaya"), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
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
            Text(if (agent) "🤖 Saathi" else "🏥 Clinic", style = MaterialTheme.typography.labelLarge, color = if (agent) SaffronDark else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
