package com.saathi.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.features.scam.ReasonText
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import java.text.DateFormat
import java.util.Date

/** Alerts history: every scam/safety event, newest first, explained in plain language. */
@Composable
fun AlertsScreen(container: AppContainer, onBack: () -> Unit) {
    SecureWindow() // private data: no screenshots or screen recording
    val settings by container.settings.flow.collectAsState()
    val style = settings.replyStyle
    val events by container.db.events().recent().collectAsState(initial = emptyList())
    val shown = events.filter { it.level != "info" || it.type != "call" }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    SaathiScaffold(tr(style, "Safety alerts", "सुरक्षा अलर्ट", "Safety alerts"), onBack) {
        InfoCard(
            title = tr(style, "Scam Shield is on", "धोखा-रक्षक चालू है", "Scam Shield on hai"),
            body = tr(style, "I check every message and warn you during suspicious calls. OTPs never leave your phone.",
                "मैं हर संदेश जाँचती हूँ और संदिग्ध कॉल पर आपको सावधान करती हूँ। ओटीपी कभी फ़ोन से बाहर नहीं जाते।",
                "Main har message check karti hoon aur suspicious call par aapko savdhaan karti hoon. OTP kabhi phone se bahar nahi jaate."),
            icon = "🛡️", accent = Leaf,
        )
        if (shown.isEmpty()) {
            EmptyNote(tr(style, "No alerts. Saathi is watching your messages and calls for scams.",
                "कोई अलर्ट नहीं। साथी आपके संदेशों और कॉल पर धोखे के लिए नज़र रख रहा है।",
                "Koi alert nahi. Saathi aapke messages aur calls par dhokhe ke liye nazar rakh raha hai."))
        }
        shown.forEach { e ->
            val (label, color) = when (e.level) {
                "red" -> tr(style, "DANGER", "खतरा", "KHATRA") to Danger
                "amber" -> tr(style, "CAUTION", "सावधानी", "SAVDHAANI") to Amber
                else -> tr(style, "INFO", "जानकारी", "INFO") to Leaf
            }
            val (icon, title) = when (e.type) {
                "call" -> "📞" to tr(style, "Suspicious call", "संदिग्ध कॉल", "Suspicious call")
                "message" -> "✉️" to tr(style, "Suspicious message", "संदिग्ध संदेश", "Suspicious message")
                "link" -> "🔗" to tr(style, "Dangerous link", "खतरनाक लिंक", "Khatarnaak link")
                else -> "🛡️" to e.type
            }
            val reasons = e.reasons.split(',').filter { it.isNotBlank() }
            InfoCard(
                title = title,
                body = listOf(ReasonText.join(reasons, style), "🕒 ${relativeTime(e.ts, style)} · ${fmt.format(Date(e.ts))}")
                    .filter { it.isNotBlank() }.joinToString("\n"),
                badge = label,
                accent = color,
                icon = icon,
            )
        }
    }
}
