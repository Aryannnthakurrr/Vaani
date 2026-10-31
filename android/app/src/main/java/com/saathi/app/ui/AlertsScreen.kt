package com.saathi.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    val settings by container.settings.flow.collectAsState()
    val style = settings.replyStyle
    val events by container.db.events().recent().collectAsState(initial = emptyList())
    val shown = events.filter { it.level != "info" || it.type != "call" }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    // The shield needs the messages-and-calls permissions; re-checked on every return to the screen.
    val ctx = LocalContext.current
    val step = remember { Permissions.steps.first { it.id == "sms_calls" } }
    var shieldOn by remember { mutableStateOf(step.granted(ctx)) }
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        shieldOn = step.granted(ctx)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        shieldOn = step.granted(ctx)
        // Android no longer shows the dialog after two refusals: the next tap opens App info instead.
        val activity = ctx as? Activity
        blocked = activity != null && result.any { (perm, ok) -> !ok && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) }
    }

    SaathiScaffold(tr(style, "Safety alerts", "सुरक्षा अलर्ट", "Safety alerts"), onBack) {
        if (shieldOn) InfoCard(
            title = tr(style, "Scam Shield is on", "धोखा-रक्षक चालू है", "Scam Shield on hai"),
            body = tr(style, "I check every message and warn you during suspicious calls. OTPs never leave your phone.",
                "मैं हर संदेश जाँचती हूँ और संदिग्ध कॉल पर आपको सावधान करती हूँ। ओटीपी कभी फ़ोन से बाहर नहीं जाते।",
                "Main har message check karti hoon aur suspicious call par aapko savdhaan karti hoon. OTP kabhi phone se bahar nahi jaate."),
            icon = "🛡️", accent = Leaf,
        ) else InfoCard(
            title = tr(style, "Scam Shield is off", "धोखा-रक्षक बंद है", "Scam Shield band hai"),
            body = if (blocked) tr(style, "Android won't ask again. Tap below, open Permissions, and allow SMS, Phone, Call logs and Contacts.",
                    "एंड्रॉइड अब दोबारा नहीं पूछेगा। नीचे दबाइए, Permissions खोलिए और SMS, फ़ोन, कॉल लॉग और संपर्क की अनुमति दीजिए।",
                    "Android ab dobara nahi poochega. Neeche dabaiye, Permissions kholiye aur SMS, Phone, Call logs aur Contacts allow kijiye.")
                else tr(style, "I can't check your messages and calls until you allow them.",
                    "जब तक आप अनुमति नहीं देंगे, मैं आपके संदेश और कॉल नहीं जाँच सकती।",
                    "Jab tak aap permission nahi denge, main aapke messages aur calls check nahi kar sakti."),
            icon = "🛡️", accent = Amber,
        ) {
            BigButton(tr(style, "Turn on Scam Shield", "धोखा-रक्षक चालू करें", "Scam Shield on karein"), {
                if (blocked) Permissions.open(ctx, Permissions.appSettingsIntent(ctx)) else launcher.launch(step.runtime.toTypedArray())
            })
        }
        if (shown.isEmpty()) {
            EmptyNote(if (shieldOn) tr(style, "No alerts. Saathi is watching your messages and calls for scams.",
                "कोई अलर्ट नहीं। साथी आपके संदेशों और कॉल पर धोखे के लिए नज़र रख रहा है।",
                "Koi alert nahi. Saathi aapke messages aur calls par dhokhe ke liye nazar rakh raha hai.")
                else tr(style, "No alerts yet.", "अभी कोई अलर्ट नहीं।", "Abhi koi alert nahi."))
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
                else -> "🛡️" to tr(style, "Safety alert", "सुरक्षा अलर्ट", "Safety alert")
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
