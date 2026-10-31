package com.saathi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.DigestItem
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.features.inbox.DigestRepository
import com.saathi.app.features.inbox.LocalClassifier
import com.saathi.app.features.inbox.LocalDigestWriter
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** "My updates": this week's bills, renewals, appointments and deliveries as big cards, with Read aloud. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DigestScreen(container: AppContainer, onBack: () -> Unit, onCallClinic: () -> Unit = {}) {
    val settings by container.settings.flow.collectAsState()
    val style = settings.replyStyle
    val rows by container.db.messages().recent().collectAsState(initial = emptyList())
    val reminders by container.db.reminders().upcoming().collectAsState(initial = emptyList())
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val items = DigestRepository.toItems(rows.filter { it.receivedAt >= weekAgo })
    val scope = rememberCoroutineScope()
    // The reading in progress; tapping the button again stops it.
    var reading by remember { mutableStateOf<Job?>(null) }
    // Leaving the screen stops the reading too.
    DisposableEffect(Unit) { onDispose { reading?.let { it.cancel(); container.speaker.stop() } } }

    SaathiScaffold(tr(style, "My updates", "मेरी जानकारी", "Meri updates"), onBack) {
        // The spoken summary repeats the cards below, so it is only heard, not shown again.
        BigButton(
            text = if (reading != null) tr(style, "⏹ Stop reading", "⏹ पढ़ना रोको", "⏹ Padhna roko") else tr(style, "🔊 Read aloud", "🔊 पढ़कर सुनाओ", "🔊 Padhkar sunao"),
            onClick = {
                val job = reading
                if (job != null) {
                    job.cancel()
                    container.speaker.stop()
                    reading = null
                } else {
                    // Started after it is stored, so a reading that ends at once still resets the button.
                    val next = scope.launch(start = CoroutineStart.LAZY) {
                        try { runCatching { container.digest.speak() } } finally { if (reading === coroutineContext[Job]) reading = null }
                    }
                    reading = next
                    next.start()
                }
            },
        )
        if (items.isNotEmpty()) {
            val scams = items.count { it.category == "scam_suspected" }
            val bills = items.count { it.category == "bill_due" || it.category == "subscription_renewal" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bills > 0) Pill("🧾 " + tr(style, "$bills to pay", "$bills भुगतान", "$bills payments"), MaterialTheme.colorScheme.primary)
                if (scams > 0) Pill("⚠️ " + tr(style, "$scams warnings", "$scams चेतावनी", "$scams warnings"), Danger)
                Pill("✉️ " + tr(style, "${items.size} in total", "कुल ${items.size}", "Total ${items.size}"), Leaf)
            }
        }

        SectionTitle(tr(style, "This week", "इस हफ़्ते", "Is hafte"))
        if (items.isEmpty()) EmptyNote(tr(style, "Nothing new. Bills and appointments will show up here.",
            "कुछ नया नहीं। बिल और अपॉइंटमेंट यहाँ दिखेंगे।", "Kuch naya nahi. Bills aur appointments yahan dikhenge."))
        items.forEach { DigestCard(it, style) }

        SectionTitle(tr(style, "Reminders", "रिमाइंडर", "Reminders"))
        // The clinic call is about the last booked appointment, so it is offered only when there is one.
        if (settings.lastAppointment != null) {
            BigButton(tr(style, "📞 Call the clinic for me", "📞 मेरे लिए क्लिनिक को फ़ोन करो", "📞 Mere liye clinic ko phone karo"), onCallClinic,
                color = Leaf)
        }
        if (reminders.isEmpty()) EmptyNote(tr(style, "No reminders yet.", "अभी कोई रिमाइंडर नहीं।", "Abhi koi reminder nahi."))
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        reminders.forEach { r -> InfoCard(title = r.title, body = r.body, badge = fmt.format(Date(r.triggerAt)), icon = "⏰", accent = Amber) }
    }
}

@Composable
private fun DigestCard(item: DigestItem, style: ReplyStyle) {
    val (icon, badge, color) = when (item.category) {
        "bill_due" -> Triple("🧾", tr(style, "Bill", "बिल", "Bill"), MaterialTheme.colorScheme.primary)
        "subscription_renewal" -> Triple("🔁", tr(style, "Renewal", "रिन्यूअल", "Renewal"), Amber)
        "appointment" -> Triple("🩺", tr(style, "Appointment", "अपॉइंटमेंट", "Appointment"), Leaf)
        "delivery" -> Triple("📦", tr(style, "Delivery", "डिलीवरी", "Delivery"), Leaf)
        "payment_done" -> Triple("✅", tr(style, "Paid", "भुगतान हुआ", "Paid"), Leaf)
        "scam_suspected" -> Triple("⚠️", tr(style, "Possible scam", "धोखा हो सकता है", "Dhokha ho sakta hai"), Danger)
        else -> Triple("✉️", tr(style, "Message", "संदेश", "Message"), MaterialTheme.colorScheme.primary)
    }
    val due = item.dueDate?.let { d ->
        runCatching { LocalDigestWriter.relative(LocalDate.parse(d), LocalDate.now(), style) }.getOrNull()
    }
    val amount = item.amount?.let { "₹" + LocalClassifier.formatRupees(it) }
    val title = listOfNotNull(item.merchant, amount).joinToString(" · ")
        .ifEmpty { item.summary.let { if (it.length > 90) it.take(88).trimEnd() + "…" else it } }
    val warning = if (item.category == "scam_suspected") {
        tr(style, "Do not reply or share any code.", "जवाब मत दीजिए, कोई कोड मत बताइए।", "Jawab mat dijiye, koi code mat bataiye.")
    } else null
    val body = listOfNotNull(due?.let { d -> tr(style, "Due $d", d.removeSuffix(" को") + " तक", d.removeSuffix(" ko") + " tak") }, item.summary.takeIf { item.merchant == null && !title.startsWith(it.take(40)) }, warning)
        .joinToString("\n").ifEmpty { null }
    InfoCard(title = title, body = body, badge = badge, accent = color, icon = icon)
}
