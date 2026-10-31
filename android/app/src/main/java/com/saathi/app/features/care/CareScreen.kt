package com.saathi.app.features.care

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.Pill
import com.saathi.app.ui.SaathiScaffold
import com.saathi.app.ui.SecondaryButton
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
    val ui by c.care.ui.collectAsState()
    val asking by c.care.asking.collectAsState()
    val style = c.settings.current.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (ui.phase == CarePhase.STARTING && c.care.sessionId == null) c.care.start() }
    val booked = ui.booking?.takeIf { it.status == "confirmed" }

    SaathiScaffold(s("Doctor", "डॉक्टर", "Doctor"), { c.care.stop(); onBack() }) {
        if (booked != null) {
            Booked(booked, c, ::s)
            return@SaathiScaffold
        }
        Text(s("I am an AI assistant, not a doctor. In an emergency, call 108.", "मैं AI सहायक हूँ, डॉक्टर नहीं। इमरजेंसी में 108 पर फ़ोन करें।",
            "Main AI assistant hoon, doctor nahi. Emergency mein 108 par call karein."), fontSize = 16.sp, lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ui.chat.takeLast(6).forEach { Bubble(it) }
        if (ui.phase != CarePhase.RECOMMEND) Answering(asking, c, ::s)

        ui.note?.let { note -> NoteCard(note, ::s) }

        val board = ui.hospitals.filter { it.calling || it.result != null }
        if (board.isNotEmpty() && ui.recommendation == null) board.forEach { HospitalCard(it, ::s) }

        ui.recommendation?.takeIf { ui.phase == CarePhase.RECOMMEND }?.let { rec -> RecommendationCard(rec, ui, c, ::s) }

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
                SecondaryButton("${h.name}${h.etaMinutes?.let { " · ${it.toInt()} min" }.orEmpty()}",
                    { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${h.phone}"))) })
            }
        }
        if (!c.care.running && ui.phase != CarePhase.STARTING) {
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
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (line.fromUser) 22.dp else 6.dp, bottomEnd = if (line.fromUser) 6.dp else 22.dp),
            color = if (line.fromUser) Accent50 else Color.White,
            border = if (line.fromUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 320.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun Answering(asking: CareFlow.Asking, c: AppContainer, s: (String, String, String) -> String) {
    when (asking) {
        CareFlow.Asking.YES_NO -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(s("Yes", "हाँ", "Haan"), { c.care.tap(true) }, Modifier.weight(1f), color = Leaf)
            SecondaryButton(s("No", "नहीं", "Nahi"), { c.care.tap(false) }, Modifier.weight(1f))
        }
        CareFlow.Asking.TEXT -> {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(s("Speak, or type here", "बोलिए, या यहाँ लिखिए", "Boliye, ya yahan likhiye")) })
            if (text.isNotBlank()) BigButton(s("Send", "भेजें", "Bhejein"), { c.care.offerText(text); text = "" })
        }
        CareFlow.Asking.NOTHING -> Unit
    }
}

@Composable
private fun NoteCard(note: IntakeNote, s: (String, String, String) -> String) {
    var open by remember { mutableStateOf(false) }
    val urgent = note.emergency.answer != "NO"
    Surface(shape = RoundedCornerShape(20.dp), color = if (urgent) Danger50 else Accent50, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (urgent) s("Could be an emergency", "इमरजेंसी हो सकती है", "Emergency ho sakti hai")
                else s("See a ${note.specialty} doctor", "${note.specialty} डॉक्टर को दिखाएँ", "${note.specialty} doctor ko dikhayein"),
                style = MaterialTheme.typography.titleLarge)
            Text(s("AI-generated. Not a diagnosis.", "AI द्वारा। यह निदान नहीं है।", "AI se. Diagnosis nahi."), fontSize = 15.sp, color = Muted)
            TextButton({ open = !open }) { Text(if (open) s("Hide note", "नोट छिपाएँ", "Note chhupayein") else s("Show note", "नोट देखें", "Note dekhein")) }
            if (open) Text(note.noteText, fontSize = 14.sp)
        }
    }
}

/** One hospital being called: name, distance, and a small status pill instead of the call transcript. */
@Composable
private fun HospitalCard(row: HospitalRow, s: (String, String, String) -> String) {
    val r = row.result
    val (status, color) = when {
        r == null -> s("Calling…", "फ़ोन जारी…", "Call ho raha hai…") to Amber
        r.callOutcome != "COMPLETED" -> s("No answer", "जवाब नहीं", "Jawab nahi") to Muted
        r.specialtyAvailable == false -> s("No doctor", "डॉक्टर नहीं", "Doctor nahi") to Muted
        else -> s("Available", "उपलब्ध", "Available") to Leaf
    }
    val details = listOfNotNull(row.hospital.etaMinutes?.let { "${it.toInt()} min" }, r?.feeInr?.let { "₹$it" },
        r?.slots?.firstOrNull()?.let { CareText.time(it.start, com.saathi.app.core.net.ReplyStyle.EN) }).joinToString(" · ")
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Accent50), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalHospital, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.hospital.name, style = MaterialTheme.typography.titleMedium)
                if (details.isNotBlank()) Text(details, fontSize = 17.sp, color = Muted)
                Pill(status, color)
            }
            if (r == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = Amber)
        }
    }
}

/** The recommendation as one booking card: who, when, how far, fee, and one big "Book". */
@Composable
private fun RecommendationCard(rec: Recommendation, ui: CareUi, c: AppContainer, s: (String, String, String) -> String) {
    var more by remember { mutableStateOf(false) }
    val style = c.settings.current.replyStyle
    val p = rec.primary
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(s("Best option", "सबसे अच्छा", "Best option"), Leaf)
            Text(p.hospitalName, style = MaterialTheme.typography.headlineMedium)
            p.doctorName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(CareText.time(rec.slot.start, style), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            val facts = listOfNotNull((p.etaMinutes ?: ui.hospitals.firstOrNull { it.hospital.id == p.hospitalId }?.hospital?.etaMinutes)
                ?.let { "${it.toInt()} min" }, p.feeInr?.let { "₹$it" }).joinToString(" · ")
            if (facts.isNotBlank()) Text(facts, fontSize = 18.sp, color = Muted)
        }
    }
    com.saathi.app.ui.VoiceWave({ c.assistant.voiceLevel() },
        s("Say \"yes\" or \"no\", or tap", "\"हाँ\" या \"नहीं\" बोलिए, या दबाइए", "\"Haan\" ya \"nahi\" boliye, ya dabaiye"), Muted)
    BigButton(s("Book", "बुक करें", "Book karein"), { c.care.tap(true) }, color = Leaf)
    SecondaryButton(s("Other times", "दूसरा समय", "Doosra time"), { more = !more })
    if (more) {
        (listOf(rec.primary) + rec.alternatives).forEach { r ->
            r.slots.forEach { slot ->
                SecondaryButton("${r.hospitalName} · ${CareText.time(slot.start, style)}", { c.care.choose(r.hospitalId, slot) })
            }
        }
    }
    TextButton({ c.care.tap(false) }, Modifier.fillMaxWidth()) {
        Text(s("No thanks", "नहीं, धन्यवाद", "Nahi, shukriya"), style = MaterialTheme.typography.titleMedium, color = Muted)
    }
}

@Composable
private fun Working(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** The end of the doctor flow: a big tick, when and where, and two quiet follow-ups. */
@Composable
private fun Booked(b: BookingStatus, c: AppContainer, s: (String, String, String) -> String) {
    val context = LocalContext.current
    val style = c.settings.current.replyStyle
    val whenText = (b.startIso ?: b.calendar?.startIso)?.let { CareText.time(it, style) }.orEmpty()
    SuccessView(
        title = s("Booked", "बुक हो गया", "Book ho gaya"),
        line = listOfNotNull(b.doctorName ?: b.calendar?.title, whenText.ifBlank { null }).joinToString(", "),
        note = listOfNotNull(
            b.calendar?.let { s("Added to your calendar", "कैलेंडर में जोड़ दिया", "Calendar mein jod diya") },
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
