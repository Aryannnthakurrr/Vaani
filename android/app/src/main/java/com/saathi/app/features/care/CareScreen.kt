package com.saathi.app.features.care

import com.saathi.app.ui.theme.Space
import com.saathi.app.ui.theme.Border
import com.saathi.app.ui.theme.OnFill
import com.saathi.app.ui.theme.Paper
import android.app.Activity
import android.content.ContextWrapper
import com.saathi.app.ui.SecureWindow
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.Pill
import com.saathi.app.ui.SaathiScaffold
import com.saathi.app.ui.SecondaryButton
import com.saathi.app.ui.riseIn
import com.saathi.app.ui.SuccessView
import com.saathi.app.ui.theme.Accent50
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Muted

/**
 * The symptom-to-care screen (docs/CARE.md): the conversation as a chat, the hospitals being called, one clear
 * "Book" card, and a success screen at the end. Everything here is also spoken by [CareFlow].
 */
@Composable
fun CareScreen(c: AppContainer, onBack: () -> Unit) {
    SecureWindow() // private data: no screenshots or screen recording
    val ui by c.care.ui.collectAsState()
    val asking by c.care.asking.collectAsState()
    val settings by c.settings.flow.collectAsState()
    // Collected so the screen follows the flow ending; the flow's own answer covers the frame right after a start.
    val active by c.care.active.collectAsState()
    val running = active || c.care.running
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val context = LocalContext.current
    // This screen is the one place a doctor conversation starts when the user opens it. Saved with this screen's
    // back-stack entry, so coming back to it (from the emergency screen, after a rotation) never starts a new intake
    // over one the user ended or handed over; "Start again" is there for that.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (opened) return@LaunchedEffect
        c.care.start() // does nothing when the assistant or the emergency screen already started it
        opened = true
    }
    // Leaving by any route (back gesture, Home, another tile) ends the flow and its calls, except the hand-over to
    // the emergency screen (route A), which must keep running, and a rotation, which rebuilds this screen at once.
    val activity = context.activity()
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true && c.care.ui.value.route != "A") c.care.stop() }
    }
    val booked = ui.booking?.takeIf { it.status == "confirmed" }
    val speaking by c.speaker.speaking.collectAsState()

    SaathiScaffold(s("Doctor", "डॉक्टर", "Doctor"), { c.care.stop(); onBack() }) {
        if (booked != null) {
            Booked(booked, ui.calendarAdded, style, ::s)
            return@SaathiScaffold
        }
        Text(s("I am an AI assistant, not a doctor. In an emergency, call 108.", "मैं AI सहायक हूँ, डॉक्टर नहीं। इमरजेंसी में 108 पर फ़ोन करें।",
            "Main AI assistant hoon, doctor nahi. Emergency mein 108 par call karein."), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Keyed by position in the whole conversation, so only a new message animates in, not the ones already shown.
        ui.chat.withIndex().toList().takeLast(6).forEach { (i, line) -> key(i) { Bubble(line) } }
        // Getting ready, thinking or searching: say so, instead of a screen with nothing happening on it.
        if (asking == CareFlow.Asking.NOTHING && running) when (ui.phase) {
            CarePhase.STARTING -> Working(s("Getting ready…", "तैयारी हो रही है…", "Taiyaari ho rahi hai…"))
            CarePhase.THINKING -> Working(s("Thinking…", "सोच रही हूँ…", "Soch rahi hoon…"))
            CarePhase.SEARCHING -> Working(s("Finding hospitals near you…", "आपके पास के अस्पताल ढूँढ रही हूँ…", "Aapke paas ke hospital dhoondh rahi hoon…"))
            else -> Unit
        }
        // The voice is always visible while Saathi talks or listens here (the booking card has its own wave).
        if (ui.phase != CarePhase.RECOMMEND && ui.phase != CarePhase.ENDED &&
            (asking != CareFlow.Asking.NOTHING || (ui.phase == CarePhase.INTAKE && running) || speaking)) {
            com.saathi.app.ui.VoiceWave({ c.assistant.voiceLevel() },
                if (asking == CareFlow.Asking.YES_NO) s("Say \"yes\" or \"no\", or tap", "\"हाँ\" या \"नहीं\" बोलिए, या दबाइए", "\"Haan\" ya \"nahi\" boliye, ya dabaiye")
                else s("Speak freely, I'm listening", "बेझिझक बोलिए, मैं सुन रही हूँ", "Aaram se boliye, main sun rahi hoon"),
                MaterialTheme.colorScheme.primary)
        }
        // Mute keeps the intake conversation open while the user talks to someone else; Saathi just stops hearing.
        val intake by c.care.liveSession.collectAsState()
        intake?.takeIf { ui.phase == CarePhase.INTAKE }?.let { session ->
            val muted by session.muted.collectAsState()
            SecondaryButton(
                if (muted) s("Mic is off. Tap to unmute", "माइक बंद है। चालू करने के लिए दबाइए", "Mic band hai. Chalu karne ke liye dabaiye")
                else s("Mute my microphone", "मेरा माइक बंद करें", "Mera mic band karein"),
                { session.setMuted(!muted) })
        }
        if (ui.phase != CarePhase.RECOMMEND) Answering(asking, c, ::s)

        ui.note?.let { note -> NoteCard(note, ::s) }

        val board = ui.hospitals.filter { it.calling || it.result != null }
        if (board.isNotEmpty() && ui.recommendation == null) board.forEach { HospitalCard(it, style, ::s) }

        ui.recommendation?.takeIf { ui.phase == CarePhase.RECOMMEND }?.let { rec -> RecommendationCard(rec, ui, running, c, style, ::s) }

        ui.booking?.let { b ->
            when (b.status) {
                "calling" -> Working(s("Calling the hospital to book…", "बुक करने के लिए अस्पताल को फ़ोन कर रहे हैं…",
                    "Book karne ke liye hospital ko call kar rahe hain…"))
                else -> b.callYourself?.let { phone ->
                    BigButton(s("Call to book: $phone", "बुक करने के लिए फ़ोन करें: $phone", "Book karne ke liye call karein: $phone"),
                        { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) })
                }
            }
        }

        if (ui.phase == CarePhase.NO_OPTIONS || ui.phase == CarePhase.ERROR) {
            ui.message?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            ui.fallback.ifEmpty { ui.hospitals.map { it.hospital } }.filter { it.phone != null }.take(5).forEach { h ->
                SecondaryButton("${h.name}${h.etaMinutes?.let { " · " + minutes(it, ::s) }.orEmpty()}",
                    { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${h.phone}"))) })
            }
        }
        if (opened && !running) {
            SecondaryButton(s("Start again", "फिर से शुरू करें", "Phir se shuru karein"), { c.care.start() })
        }
        TextButton({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108"))) }, Modifier.fillMaxWidth()) {
            Text(s("Emergency? Call 108", "इमरजेंसी? 108 पर फ़ोन करें", "Emergency? 108 par call karein"),
                style = MaterialTheme.typography.titleMedium, color = Danger)
        }
    }
}

/** Saathi on the left in white, the user on the right in soft teal, like any chat. */
@Composable
private fun Bubble(line: ChatLine) {
    // Each message slides in from its own side, like any chat.
    Box(Modifier.fillMaxWidth().riseIn(fromY = 8.dp, fromX = if (line.fromUser) 40.dp else (-40).dp),
        contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (line.fromUser) 22.dp else 6.dp, bottomEnd = if (line.fromUser) 6.dp else 22.dp),
            color = if (line.fromUser) Accent50 else OnFill,
            border = if (line.fromUser) null else BorderStroke(Border.hairline, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 320.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md))
        }
    }
}

@Composable
private fun Answering(asking: CareFlow.Asking, c: AppContainer, s: (String, String, String) -> String) {
    when (asking) {
        CareFlow.Asking.YES_NO -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            BigButton(s("Yes", "हाँ", "Haan"), { c.care.tap(true) }, Modifier.weight(1f), color = Leaf)
            SecondaryButton(s("No", "नहीं", "Nahi"), { c.care.tap(false) }, Modifier.weight(1f))
        }
        CareFlow.Asking.TEXT -> {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.medium,
                // The wave above already says "speak"; the box only offers typing.
                placeholder = { Text(s("Or type here", "या यहाँ लिखिए", "Ya yahan likhiye")) })
            if (text.isNotBlank()) BigButton(s("Send", "भेजें", "Bhejein"), { c.care.offerText(text); text = "" })
        }
        CareFlow.Asking.NOTHING -> Unit
    }
}

@Composable
private fun NoteCard(note: IntakeNote, s: (String, String, String) -> String) {
    var open by remember { mutableStateOf(false) }
    val urgent = note.emergency.answer != "NO"
    Surface(shape = MaterialTheme.shapes.medium, color = if (urgent) Danger50 else Accent50, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(if (urgent) s("Could be an emergency", "इमरजेंसी हो सकती है", "Emergency ho sakti hai")
                else s("See a ${note.specialty} doctor", "${note.specialty} डॉक्टर को दिखाएँ", "${note.specialty} doctor ko dikhayein"),
                style = MaterialTheme.typography.titleLarge)
            Text(s("AI-generated. Not a diagnosis.", "AI द्वारा। यह निदान नहीं है।", "AI se. Diagnosis nahi."), style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton({ open = !open }) { Text(if (open) s("Hide note", "नोट छिपाएँ", "Note chhupayein") else s("Show note", "नोट देखें", "Note dekhein")) }
            if (open) Text(note.noteText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One hospital being called: name, distance, and a small status pill instead of the call transcript. */
@Composable
private fun HospitalCard(row: HospitalRow, style: ReplyStyle, s: (String, String, String) -> String) {
    val r = row.result
    val (status, color) = when {
        r == null -> s("Calling…", "फ़ोन जारी…", "Call ho raha hai…") to Amber
        r.callOutcome != "COMPLETED" -> s("No answer", "जवाब नहीं", "Jawab nahi") to Muted
        r.specialtyAvailable == false -> s("No doctor", "डॉक्टर नहीं", "Doctor nahi") to Muted
        else -> s("Available", "उपलब्ध", "Available") to Leaf
    }
    val details = listOfNotNull(row.hospital.etaMinutes?.let { minutes(it, s) }, r?.feeInr?.let { "₹$it" },
        r?.slots?.firstOrNull()?.let { CareText.time(it.start, style) }).joinToString(" · ")
    Surface(shape = MaterialTheme.shapes.medium, color = Paper, border = BorderStroke(Border.control, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).background(Accent50), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalHospital, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(row.hospital.name, style = MaterialTheme.typography.titleMedium)
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = Muted)
                Pill(status, color)
            }
            if (r == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = Amber)
        }
    }
}

/** The recommendation as one booking card: who, when, how far, fee, and one big "Book". */
@Composable
private fun RecommendationCard(rec: Recommendation, ui: CareUi, running: Boolean, c: AppContainer, style: ReplyStyle, s: (String, String, String) -> String) {
    var more by remember { mutableStateOf(false) }
    // Book and No thanks only while the flow can still take them: never buttons that do nothing.
    val answering = running && !ui.declined
    val p = rec.primary
    Surface(shape = MaterialTheme.shapes.medium, color = Paper, border = BorderStroke(Border.control, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Pill(s("Best option", "सबसे अच्छा", "Best option"), Leaf)
            Text(p.hospitalName, style = MaterialTheme.typography.headlineMedium)
            p.doctorName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(CareText.time(rec.slot.start, style), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            val facts = listOfNotNull((p.etaMinutes ?: ui.hospitals.firstOrNull { it.hospital.id == p.hospitalId }?.hospital?.etaMinutes)
                ?.let { minutes(it, s) }, p.feeInr?.let { "₹$it" }).joinToString(" · ")
            if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (answering) {
        com.saathi.app.ui.VoiceWave({ c.assistant.voiceLevel() },
            s("Say \"yes\" or \"no\", or tap", "\"हाँ\" या \"नहीं\" बोलिए, या दबाइए", "\"Haan\" ya \"nahi\" boliye, ya dabaiye"), MaterialTheme.colorScheme.primary)
        BigButton(s("Book", "बुक करें", "Book karein"), { c.care.tap(true) }, color = Leaf)
    }
    // After a no, the other times are simply listed: each one books with a tap.
    if (running && !ui.declined) SecondaryButton(s("Other times", "दूसरा समय", "Doosra time"), { more = !more })
    if (running && (more || ui.declined)) {
        (listOf(rec.primary) + rec.alternatives).forEach { r ->
            r.slots.forEach { slot ->
                SecondaryButton("${r.hospitalName} · ${CareText.time(slot.start, style)}", { c.care.choose(r.hospitalId, slot) })
            }
        }
    }
    if (answering) {
        TextButton({ c.care.tap(false) }, Modifier.fillMaxWidth()) {
            Text(s("No thanks", "नहीं, धन्यवाद", "Nahi, shukriya"), style = MaterialTheme.typography.titleMedium, color = Muted)
        }
    }
}

/** "12 min" / "12 मिनट": a travel time in the user's language. */
private fun minutes(m: Double, s: (String, String, String) -> String): String = m.toInt().let { s("$it min", "$it मिनट", "$it min") }

/** The activity behind a Compose context, which may be wrapped (theme, locale). */
private tailrec fun android.content.Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun Working(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** The end of the doctor flow: a big tick, when and where, and two quiet follow-ups. */
@Composable
private fun Booked(b: BookingStatus, calendarAdded: Boolean, style: ReplyStyle, s: (String, String, String) -> String) {
    val context = LocalContext.current
    val whenText = (b.startIso ?: b.calendar?.startIso)?.let { CareText.time(it, style) }.orEmpty()
    SuccessView(
        title = s("Booked", "बुक हो गया", "Book ho gaya"),
        line = listOfNotNull(b.doctorName ?: b.calendar?.title, whenText.ifBlank { null }).joinToString(", "),
        note = listOfNotNull(
            // Only once the user said yes and it was written; the question itself is asked by voice.
            if (calendarAdded) s("Added to your calendar", "कैलेंडर में जोड़ दिया", "Calendar mein jod diya") else null,
            b.bookingRef?.let { "Ref $it" },
        ).joinToString(" · ").ifBlank { null },
    )
    b.instructions?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Muted) }
    b.calendar?.navigateUrl?.let { url ->
        BigButton(s("Show the way", "रास्ता दिखाएँ", "Rasta dikhayein"), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
    }
    val summary = listOfNotNull(b.calendar?.title, whenText.ifBlank { null }, b.bookingRef?.let { "Ref $it" }).joinToString(", ")
    SecondaryButton(s("Share with family", "परिवार को भेजें", "Family ko bhejein"), {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(summary))))
    })
}
