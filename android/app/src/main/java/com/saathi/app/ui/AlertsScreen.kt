package com.saathi.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.EventEntity
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.features.scam.ReasonText
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Edge
import com.saathi.app.ui.theme.Ink
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import com.saathi.app.ui.theme.Muted
import com.saathi.app.ui.theme.Soft
import kotlinx.coroutines.launch

/**
 * Safety alerts (Stitch "3.4"): a Listen card that reads the alerts aloud, the Scam Shield status, every
 * scam/safety event newest first with what to do about it, and a way to ask Saathi.
 */
@Composable
fun AlertsScreen(container: AppContainer, onBack: () -> Unit) {
    SecureWindow() // private data: no screenshots or screen recording
    val c = container
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    fun a(en: String, hi: String) = alt(style, en, hi)
    val events by c.db.events().recent().collectAsState(initial = emptyList())
    val shown = events.filter { it.level != "info" || it.type != "call" }
    val scope = rememberCoroutineScope()
    val speaking by c.speaker.speaking.collectAsState()

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

    SaathiScaffold(s("Safety alerts", "सुरक्षा अलर्ट", "Safety alerts"), onBack, subtitle = a("Safety alerts", "सुरक्षा अलर्ट")) {
        // Listen: the shield's state and the newest alerts, spoken in the user's language.
        ListenCard(
            title = s("Listen", "बोलकर सुनें", "Suniye"), second = a("Listen", "बोलकर सुनें"),
            line = s("Hear your latest safety news", "सुरक्षा की ताज़ा जानकारी सुनें", "Suraksha ki taaza jaankari suniye"),
            playing = speaking,
            onPlay = {
                if (speaking) c.speaker.stop()
                else scope.launch { c.speaker.say(spokenSummary(shieldOn, shown, style), style) }
            },
            playLabel = if (speaking) s("Stop reading", "पढ़ना रोकें", "Padhna rokein") else s("Read aloud", "पढ़कर सुनाएँ", "Padh kar sunaiye"),
        )

        if (shieldOn) SectionCard(
            Icons.Filled.GppGood, s("Scam Shield is on", "धोखा-रक्षक चालू है", "Scam Shield on hai"),
            a("Scam Shield is on", "धोखा सुरक्षा पूरी तरह चालू है"), tint = Leaf, tintBg = Leaf50,
            trailing = { Icon(Icons.Filled.CheckCircle, null, tint = Leaf, modifier = Modifier.size(30.dp)) },
        ) {
            StatusPill(s("ACTIVE", "सक्रिय", "ACTIVE") + " • " + a("ACTIVE", "सक्रिय"), Leaf, dot = true)
            Surface(shape = RoundedCornerShape(12.dp), color = Soft) {
                TwoLine(
                    s("I check every message and warn you during suspicious calls. OTPs never leave your phone.",
                        "मैं हर संदेश जाँचती हूँ और संदिग्ध कॉल पर आपको सावधान करती हूँ। ओटीपी कभी फ़ोन से बाहर नहीं जाते।",
                        "Main har message check karti hoon aur suspicious call par aapko savdhaan karti hoon. OTP kabhi phone se bahar nahi jaate."),
                    a("I check every message and warn you during suspicious calls. OTPs never leave your phone.",
                        "हम हर संदेश और कॉल पर नज़र रखते हैं। आपकी जानकारी व ओटीपी हमेशा सुरक्षित हैं।"),
                    Modifier.padding(16.dp), mainStyle = MaterialTheme.typography.bodyLarge, mainColor = Ink,
                )
            }
            Row(Modifier.clip(RoundedCornerShape(50)).background(Leaf50).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, null, tint = Leaf, modifier = Modifier.size(20.dp))
                Text(s("Checked on this phone", "जाँच इसी फ़ोन पर होती है", "Check isi phone par hota hai"),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Leaf)
            }
        } else SectionCard(
            Icons.Filled.GppMaybe, s("Scam Shield is off", "धोखा-रक्षक बंद है", "Scam Shield band hai"),
            a("Scam Shield is off", "धोखा-रक्षक बंद है"), tint = Amber, tintBg = Amber50,
        ) {
            Text(if (blocked) s("Android won't ask again. Tap below, open Permissions, and allow SMS, Phone, Call logs and Contacts.",
                    "एंड्रॉइड अब दोबारा नहीं पूछेगा। नीचे दबाइए, Permissions खोलिए और SMS, फ़ोन, कॉल लॉग और संपर्क की अनुमति दीजिए।",
                    "Android ab dobara nahi poochega. Neeche dabaiye, Permissions kholiye aur SMS, Phone, Call logs aur Contacts allow kijiye.")
                else s("I can't check your messages and calls until you allow them.",
                    "जब तक आप अनुमति नहीं देंगे, मैं आपके संदेश और कॉल नहीं जाँच सकती।",
                    "Jab tak aap permission nahi denge, main aapke messages aur calls check nahi kar sakti."),
                style = MaterialTheme.typography.bodyLarge)
            IconButtonBig(s("Turn on Scam Shield", "धोखा-रक्षक चालू करें", "Scam Shield on karein"), Icons.Outlined.Shield, {
                if (blocked) Permissions.open(ctx, Permissions.appSettingsIntent(ctx)) else launcher.launch(step.runtime.toTypedArray())
            }, second = a("Turn on Scam Shield", "धोखा-रक्षक चालू करें"))
        }

        // "Recent alerts" with how many came in the last day.
        val fresh = shown.count { it.ts >= System.currentTimeMillis() - 24 * 3600_000L }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
            Text(s("Recent alerts", "हाल के अलर्ट", "Recent alerts"), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (fresh > 0) Text(s("$fresh new", "$fresh नए", "$fresh naye"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        }
        if (shown.isEmpty()) {
            EmptyNote(if (shieldOn) s("No alerts. Saathi is watching your messages and calls for scams.",
                "कोई अलर्ट नहीं। साथी आपके संदेशों और कॉल पर धोखे के लिए नज़र रख रहा है।",
                "Koi alert nahi. Saathi aapke messages aur calls par dhokhe ke liye nazar rakh raha hai.")
                else s("No alerts yet.", "अभी कोई अलर्ट नहीं।", "Abhi koi alert nahi."))
        }
        shown.forEach { e -> AlertCard(e, c, style, settings.notifyFamilyOnScam, settings.contacts.map { it.name }) }

        // Ask for help: closes this screen and opens the conversation on Home.
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.5.dp, Edge), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBubble(Icons.Filled.RecordVoiceOver, Color.White, MaterialTheme.colorScheme.primary, size = 60.dp, round = true)
                Text(s("Need help from Saathi?", "साथी से मदद चाहिए?", "Saathi se madad chahiye?"), style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center)
                Text(s("If something is unclear, just ask out loud. I am here to help.",
                    "अगर कोई बात समझ न आए, तो बस बोलकर पूछें। हम आपकी मदद के लिए यहाँ हैं।",
                    "Agar koi baat samajh na aaye, to bas bol kar poochiye. Main madad ke liye yahan hoon."),
                    style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Center)
                IconButtonBig(s("Speak to Saathi", "साथी से बोलें", "Saathi se boliye"), Icons.Filled.Mic,
                    { onBack(); c.assistant.onMicTapped() }, second = a("Speak to Saathi", "बोलिए / साथी से बात करें"))
            }
        }
    }
}

/** What "Listen" says: the shield, then the three newest alerts in a sentence each. */
private fun spokenSummary(shieldOn: Boolean, events: List<EventEntity>, style: ReplyStyle): String {
    val shield = if (shieldOn) tr(style, "Scam Shield is on.", "धोखा-रक्षक चालू है।", "Scam Shield on hai.")
        else tr(style, "Scam Shield is off.", "धोखा-रक्षक बंद है।", "Scam Shield band hai.")
    if (events.isEmpty()) return shield + " " + tr(style, "There are no alerts.", "कोई अलर्ट नहीं है।", "Koi alert nahi hai.")
    val lines = events.take(3).map { e ->
        "${kindTitle(e.type, style)}, ${relativeTime(e.ts, style)}. " + ReasonText.join(e.reasons.split(',').filter { it.isNotBlank() }, style)
    }
    return (listOf(shield, tr(style, "Your latest alerts:", "आपके ताज़ा अलर्ट:", "Aapke latest alerts:")) + lines).joinToString(" ")
}

private fun kindTitle(type: String, style: ReplyStyle) = when (type) {
    "call" -> tr(style, "Suspicious call", "संदिग्ध कॉल", "Suspicious call")
    "message" -> tr(style, "Suspicious message", "संदिग्ध संदेश", "Suspicious message")
    "link" -> tr(style, "Dangerous link", "खतरनाक लिंक", "Khatarnaak link")
    else -> tr(style, "Safety alert", "सुरक्षा अलर्ट", "Safety alert")
}

@Composable
private fun ListenCard(title: String, second: String, line: String, playing: Boolean, onPlay: () -> Unit, playLabel: String) {
    val (press, pressMotion) = rememberPress()
    Surface(onClick = onPlay, interactionSource = press, shape = RoundedCornerShape(16.dp), color = Leaf50,
        border = BorderStroke(1.5.dp, Leaf.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().then(pressMotion).semantics(mergeDescendants = true) {
            role = Role.Button; contentDescription = "$title. $line. $playLabel"
        }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBubble(Icons.Filled.VolumeUp, Color.White, MaterialTheme.colorScheme.primary, size = 52.dp, round = true)
            Column(Modifier.weight(1f)) {
                TwoLine(title, second, mainStyle = MaterialTheme.typography.titleMedium, secondColor = Leaf)
                Text(line, fontSize = 17.sp, lineHeight = 23.sp, color = Ink)
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * One alert: level pill and time, the kind of alert with its second-language line, the reason in a tinted box,
 * the message itself for message alerts, and what the user can do about it.
 */
@Composable
private fun AlertCard(e: EventEntity, c: AppContainer, style: ReplyStyle, familyOn: Boolean, family: List<String>) {
    val ctx = LocalContext.current
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    fun a(en: String, hi: String) = alt(style, en, hi)
    val (label, color, tint) = when (e.level) {
        "red" -> Triple(s("DANGER", "खतरा", "KHATRA") + " • " + a("DANGER", "खतरा"), Danger, Danger50)
        "amber" -> Triple(s("CAUTION", "सावधानी", "SAVDHAANI") + " • " + a("CAUTION", "सावधानी"), Amber, Amber50)
        else -> Triple(s("INFO", "जानकारी", "INFO") + " • " + a("INFO", "जानकारी"), Leaf, Leaf50)
    }
    val (icon, second) = when (e.type) {
        "call" -> Icons.Filled.PhoneDisabled to a("Suspicious call", "संदिग्ध कॉल")
        "message" -> Icons.AutoMirrored.Filled.Message to a("Suspicious message", "संदिग्ध संदेश")
        "link" -> Icons.Filled.LinkOff to a("Dangerous link", "फ़र्ज़ी लिंक से बचें")
        else -> Icons.Filled.Warning to a("Safety alert", "सुरक्षा अलर्ट")
    }
    val reasons = e.reasons.split(',').filter { it.isNotBlank() }
    val reason = ReasonText.join(reasons, style)
    val reasonAlt = ReasonText.join(reasons, if (style == ReplyStyle.HI) ReplyStyle.EN else ReplyStyle.HI)
    // The message the alert is about, already redacted when it was stored (no OTPs or account numbers).
    val message by produceState<String?>(null, e.sourceMsgId) {
        value = e.sourceMsgId?.let { c.db.messages().byId(it)?.redactedText }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.5.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(label, color, icon = if (e.level == "red") Icons.Filled.Warning else null)
                Text(relativeTime(e.ts, style), fontSize = 17.sp, color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBubble(icon, color, tint, size = 52.dp, round = true)
                TwoLine(kindTitle(e.type, style), second, Modifier.weight(1f), mainStyle = MaterialTheme.typography.titleLarge,
                    secondColor = color)
            }
            if (reason.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.6f)) {
                TwoLine(reason, reasonAlt, Modifier.fillMaxWidth().padding(14.dp), mainStyle = MaterialTheme.typography.bodyLarge,
                    mainColor = Ink)
            }
            message?.takeIf { it.isNotBlank() }?.let { text ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(22.dp).offset(y = 2.dp))
                    Text("“${text.take(120)}${if (text.length > 120) "…" else ""}”", fontSize = 18.sp, lineHeight = 25.sp,
                        fontStyle = FontStyle.Italic, color = Ink)
                }
            }
            when (e.type) {
                "call" -> {
                    if (e.level != "info") IconButtonBig(s("Block a number", "नंबर ब्लॉक करें", "Number block karein"), Icons.Filled.Block,
                        { openBlockedNumbers(ctx) }, second = a("Block a number", "हमेशा के लिए ब्लॉक करें"), color = Danger)
                    IconButtonBig(s("View call log", "कॉल की सूची देखें", "Call list dekhiye"), Icons.Filled.Call, { openCallLog(ctx) },
                        color = Color.White, contentColor = Ink, border = Edge, height = 56.dp)
                }
                "message", "link" -> IconButtonBig(s("Open Messages", "संदेश खोलें", "Messages kholiye"), Icons.AutoMirrored.Filled.Message,
                    { openMessages(ctx) }, color = Color.White, contentColor = Ink, border = Edge, height = 56.dp)
            }
            // A red call is reported to family on Telegram when they are set up (CallGuard).
            if (e.type == "call" && e.level == "red" && familyOn && family.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    family.take(3).forEach { n ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Leaf50), contentAlignment = Alignment.Center) {
                            Text(n.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Leaf, fontSize = 17.sp)
                        }
                    }
                    Text(s("Family is told on Telegram", "परिवार को टेलीग्राम पर बताया जाता है", "Family ko Telegram par bataya jaata hai"),
                        fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Leaf)
                }
            }
        }
    }
}

/** Android's own blocked-numbers list, where any number can be blocked for good. */
private fun openBlockedNumbers(ctx: Context) {
    val tm = ctx.getSystemService(TelecomManager::class.java)
    runCatching { ctx.startActivity(tm.createManageBlockedNumbersIntent()) }
        .onFailure { openCallLog(ctx) }
}

private fun openCallLog(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)) }
}

private fun openMessages(ctx: Context) {
    runCatching { ctx.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)) }
}
