package com.saathi.app.features.care

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.InfoCard
import com.saathi.app.ui.SaathiScaffold
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf

/**
 * The symptom-to-care screen (docs/CARE.md): the spoken conversation in large text, the intake note, the live
 * hospital-call board, the recommendation and the booking. Everything here is also spoken by [CareFlow].
 */
@Composable
fun CareScreen(c: AppContainer, onBack: () -> Unit) {
    val ui by c.care.ui.collectAsState()
    val asking by c.care.asking.collectAsState()
    val style = c.settings.current.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (ui.phase == CarePhase.STARTING && c.care.sessionId == null) c.care.start() }

    SaathiScaffold(s("Not feeling well?", "तबीयत ठीक नहीं?", "Tabiyat theek nahi?"), { c.care.stop(); onBack() }) {
        Text(s("I am an AI assistant, not a doctor. In an emergency, call 108.", "मैं AI सहायक हूँ, डॉक्टर नहीं। इमरजेंसी में 108 पर फ़ोन करें।",
            "Main AI assistant hoon, doctor nahi. Emergency mein 108 par call karein."), fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ui.chat.takeLast(6).forEach { line ->
            InfoCard(line.text, accent = if (line.fromUser) Leaf else null, icon = if (line.fromUser) "🗣️" else "🤝",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (ui.phase != CarePhase.RECOMMEND) Answering(asking, c, ::s)

        ui.note?.let { note -> NoteCard(note, ::s) }

        if (ui.hospitals.isNotEmpty()) {
            Text(s("Hospitals", "अस्पताल", "Hospitals"), style = MaterialTheme.typography.titleLarge)
            ui.hospitals.filter { it.calling || it.result != null }.forEach { HospitalCard(it, ::s) }
        }

        ui.recommendation?.takeIf { ui.phase == CarePhase.RECOMMEND }?.let { rec -> RecommendationCard(rec, c, ::s) }

        ui.booking?.let { b ->
            when (b.status) {
                "confirmed" -> {
                    InfoCard(s("✅ Booked", "✅ बुक हो गया", "✅ Book ho gaya"),
                        listOfNotNull(b.doctorName, b.startIso?.let { CareText.time(it, style) }, b.bookingRef?.let { "Ref $it" },
                            b.instructions).joinToString(" · "), accent = Leaf)
                    b.calendar?.navigateUrl?.let { url ->
                        BigButton(s("🧭 Navigate", "🧭 रास्ता दिखाएँ", "🧭 Rasta dikhayein"),
                            { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
                    }
                    val summary = listOfNotNull(b.calendar?.title, b.startIso?.let { CareText.time(it, style) }, b.bookingRef?.let { "Ref $it" })
                        .joinToString(", ")
                    BigButton(s("Share", "शेयर करें", "Share karein"), {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(summary))))
                    })
                }
                "calling" -> InfoCard(s("Calling the hospital to book…", "बुक करने के लिए अस्पताल को फ़ोन कर रहे हैं…",
                    "Book karne ke liye hospital ko call kar rahe hain…"), icon = "📞")
                else -> b.callYourself?.let { phone ->
                    BigButton(s("📞 Call to book: $phone", "📞 बुक करने के लिए फ़ोन करें: $phone", "📞 Book karne ke liye call karein: $phone"),
                        { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) })
                }
            }
        }

        if (ui.phase == CarePhase.NO_OPTIONS || ui.phase == CarePhase.ERROR) {
            ui.message?.let { InfoCard(it, accent = Danger, icon = "ℹ️") }
            ui.fallback.ifEmpty { ui.hospitals.map { it.hospital } }.filter { it.phone != null }.take(5).forEach { h ->
                BigButton("📞 ${h.name}${h.etaMinutes?.let { " · ${it.toInt()} min" }.orEmpty()}",
                    { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${h.phone}"))) })
            }
        }
        BigButton(s("📞 Call 108", "📞 108 पर फ़ोन करें", "📞 108 par call karein"),
            { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108"))) }, color = Danger)
        if (!c.care.running && ui.phase != CarePhase.STARTING) {
            BigButton(s("Start again", "फिर से शुरू करें", "Phir se shuru karein"), { c.care.start() })
        }
    }
}

@Composable
private fun Answering(asking: CareFlow.Asking, c: AppContainer, s: (String, String, String) -> String) {
    when (asking) {
        CareFlow.Asking.YES_NO -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(s("Yes", "हाँ", "Haan"), { c.care.tap(true) }, Modifier.weight(1f), color = Leaf)
            BigButton(s("No", "नहीं", "Nahi"), { c.care.tap(false) }, Modifier.weight(1f), color = Danger)
        }
        CareFlow.Asking.TEXT -> {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(),
                label = { Text(s("Speak after the beep, or type here", "बीप के बाद बोलिए, या यहाँ लिखिए", "Beep ke baad boliye, ya yahan likhiye")) })
            BigButton(s("Send", "भेजें", "Bhejein"), { if (text.isNotBlank()) { c.care.offerText(text); text = "" } })
        }
        CareFlow.Asking.NOTHING -> Unit
    }
}

@Composable
private fun NoteCard(note: IntakeNote, s: (String, String, String) -> String) {
    var open by remember { mutableStateOf(false) }
    val urgent = note.emergency.answer != "NO"
    InfoCard(
        title = if (urgent) s("Could be an emergency", "इमरजेंसी हो सकती है", "Emergency ho sakti hai")
        else s("Not an emergency: see a ${note.specialty} doctor", "इमरजेंसी नहीं: ${note.specialty} डॉक्टर को दिखाएँ",
            "Emergency nahi: ${note.specialty} doctor ko dikhayein"),
        body = note.possibleConditions.take(2).joinToString { it.name }.ifBlank { null }
            ?.let { s("Possible conditions: $it", "संभावित स्थिति: $it", "Possible conditions: $it") },
        accent = if (urgent) Danger else Leaf, badge = s("AI-generated. Not a diagnosis.", "AI द्वारा। यह निदान नहीं है।", "AI se. Diagnosis nahi."),
    ) {
        TextButton({ open = !open }) { Text(if (open) s("Hide note", "नोट छिपाएँ", "Note chhupayein") else s("Show the full note", "पूरा नोट देखें", "Poora note dekhein")) }
        if (open) Text(note.noteText, fontSize = 13.sp)
    }
}

@Composable
private fun HospitalCard(row: HospitalRow, s: (String, String, String) -> String) {
    val r = row.result
    val status = when {
        r == null -> s("Calling… (${row.status ?: "starting"})", "फ़ोन जारी… (${row.status ?: "शुरू"})", "Call chal raha hai… (${row.status ?: "start"})")
        r.callOutcome != "COMPLETED" -> s("No answer", "जवाब नहीं मिला", "Jawab nahi mila")
        r.specialtyAvailable == false -> s("No doctor available", "डॉक्टर उपलब्ध नहीं", "Doctor available nahi")
        else -> listOfNotNull(r.doctorName, r.slots.firstOrNull()?.let { "first slot ${it.start.take(16).replace('T', ' ')}" },
            r.feeInr?.let { "₹$it" }).joinToString(" · ").ifBlank { s("Answered", "जवाब मिला", "Jawab mila") }
    }
    InfoCard(row.hospital.name, listOfNotNull(status, row.hospital.etaMinutes?.let { "${it.toInt()} min away" }).joinToString(" · "),
        icon = "🏥") {
        row.lines.takeLast(3).forEach { Text((if (it.fromUser) "🏥 " else "🤝 ") + it.text, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun RecommendationCard(rec: Recommendation, c: AppContainer, s: (String, String, String) -> String) {
    var more by remember { mutableStateOf(false) }
    val style = c.settings.current.replyStyle
    InfoCard(s("Best option", "सबसे अच्छा विकल्प", "Best option"), rec.spokenText, accent = Leaf, icon = "⭐")
    BigButton(s("Yes, book", "हाँ, बुक करें", "Haan, book karein"), { c.care.tap(true) }, color = Leaf)
    BigButton(s("Pick another slot", "दूसरा समय चुनें", "Doosra time chunein"), { more = !more })
    BigButton(s("No thanks", "नहीं, धन्यवाद", "Nahi, shukriya"), { c.care.tap(false) }, color = Danger)
    if (more) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(rec.primary) + rec.alternatives).forEach { r ->
                r.slots.forEach { slot ->
                    BigButton("${r.hospitalName} · ${r.doctorName ?: ""} · ${CareText.time(slot.start, style)}",
                        { c.care.choose(r.hospitalId, slot) })
                }
            }
        }
    }
}
