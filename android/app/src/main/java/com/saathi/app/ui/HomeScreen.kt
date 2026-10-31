package com.saathi.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.LocalTaxi
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantState
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Coral
import com.saathi.app.ui.theme.Coral50
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import com.saathi.app.ui.theme.Muted
import com.saathi.app.ui.theme.Sky
import com.saathi.app.ui.theme.Sky50
import com.saathi.app.ui.theme.Soft
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A plain greeting, then one large white button per row, and the talk button fixed at the bottom (SPEC §6.8). */
@Composable
fun HomeScreen(c: AppContainer, navigate: (String) -> Unit) {
    val settings by c.settings.flow.collectAsState()
    val state by c.assistant.state.collectAsState()
    val heard by c.assistant.lastHeard.collectAsState()
    val caption by c.speaker.caption.collectAsState()
    val events by c.db.events().recent().collectAsState(initial = emptyList())
    val messages by c.db.messages().recent().collectAsState(initial = emptyList())
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val alerts = events.count { it.ts >= dayAgo && (it.level == "red" || it.level == "amber") }
    val updates = messages.count { it.receivedAt >= weekAgo && it.category in USEFUL }

    val live by c.assistant.live.collectAsState()
    val liveState = live?.state?.collectAsState()?.value
    val talking = live != null || state == AssistantState.LISTENING || state == AssistantState.THINKING
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { MicBar(state, liveState, style) { c.assistant.onMicTapped() } }) { pad ->
        if (talking) {
            val liveLines = live?.transcripts?.collectAsState()?.value.orEmpty()
            TalkingView(
                level = { c.assistant.voiceLevel() },
                saathiSpeaking = liveState == LiveState.SPEAKING,
                status = null, // the bar below already says listening / speaking
                // In a live conversation only its own transcript counts; an older spoken caption would be stale.
                saathiLine = liveLines.lastOrNull { it.role == "saathi" }?.text ?: (if (live == null) caption else "").ifBlank {
                    s("I'm listening. Tell me what you need.", "मैं सुन रही हूँ। बताइए क्या चाहिए।", "Main sun rahi hoon. Bataiye kya chahiye.")
                },
                userLine = liveLines.lastOrNull { it.role == "user" }?.text ?: heard,
                modifier = Modifier.padding(pad).padding(horizontal = 24.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Greeting(settings.userName, style) { navigate("settings") }

            if (alerts > 0) {
                ActionRow(s("$alerts warnings", "$alerts चेतावनी", "$alerts warnings"), Icons.Outlined.Shield, { navigate("alerts") },
                    tint = Danger, tintBg = Danger50, border = Danger.copy(alpha = 0.35f))
            }

            ActionRow(s("Doctor", "डॉक्टर", "Doctor"), Icons.Outlined.MedicalServices, { c.care.start(); c.assistant.openScreen("care") })
            ActionRow(s("Cab", "कैब", "Cab"), Icons.Outlined.LocalTaxi, { c.assistant.startTask(Tasks.UBER) },
                tint = Amber, tintBg = Amber50)
            ActionRow(s("House help", "घर की मदद", "House help"), Icons.Outlined.CleaningServices, { c.assistant.startTask(Tasks.HOUSE_HELP) },
                tint = Leaf, tintBg = Leaf50)
            ActionRow(if (updates > 0) s("Updates ($updates)", "अपडेट ($updates)", "Updates ($updates)") else s("Updates", "अपडेट", "Updates"),
                Icons.Outlined.MailOutline, { navigate("digest") }, tint = Sky, tintBg = Sky50)
            ActionRow(s("Check product", "सामान जाँचें", "Saamaan jaanchein"), Icons.Outlined.PhotoCamera, { navigate("camera") },
                tint = Coral, tintBg = Coral50)
            ActionRow(s("Teach me", "सिखाओ", "Sikhao"), Icons.AutoMirrored.Outlined.HelpOutline, { c.assistant.startGuide(null) },
                tint = Muted, tintBg = Soft)

            // Rarely needed (mostly by family): quiet links instead of big buttons.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ navigate("alerts") }, Modifier.heightIn(min = 56.dp)) {
                    Text(s("Warnings", "चेतावनी", "Warnings"), style = MaterialTheme.typography.titleMedium)
                }
                TextButton({ navigate("log") }, Modifier.heightIn(min = 56.dp)) {
                    Text(s("History", "इतिहास", "History"), style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(Modifier.padding(bottom = 8.dp))
        }
    }
}

private val USEFUL = setOf("bill_due", "subscription_renewal", "appointment", "delivery", "payment_done", "scam_suspected")

@Composable
private fun Greeting(name: String, style: ReplyStyle, onSettings: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> tr(style, "Good morning", "सुप्रभात", "Good morning")
        hour < 17 -> tr(style, "Good afternoon", "नमस्ते", "Namaste")
        else -> tr(style, "Good evening", "शुभ संध्या", "Good evening")
    } + name.takeIf { it.isNotBlank() }?.let { ", $it ji" }.orEmpty()
    val locale = if (style == ReplyStyle.HI) Locale("hi", "IN") else Locale("en", "IN")
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))

    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(greeting, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            }
            Surface(onClick = onSettings, shape = CircleShape, color = Color.White,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(64.dp).semantics { contentDescription = tr(style, "Settings", "सेटिंग्स", "Settings") }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun MicBar(state: AssistantState, live: LiveState?, style: ReplyStyle, onClick: () -> Unit) {
    val idle = state == AssistantState.IDLE
    val status = when {
        live == LiveState.CONNECTING -> tr(style, "Connecting…", "जुड़ रही हूँ…", "Connect ho rahi hoon…")
        live == LiveState.SPEAKING -> tr(style, "Saathi is speaking. You can interrupt.", "साथी बोल रहा है। आप बीच में बोल सकते हैं।",
            "Saathi bol raha hai. Aap beech mein bol sakte hain.")
        live == LiveState.LISTENING -> tr(style, "Talk freely. I'm listening.", "बेझिझक बोलिए। मैं सुन रही हूँ।", "Aaram se boliye. Main sun rahi hoon.")
        else -> when (state) {
            AssistantState.LISTENING -> tr(style, "Listening… speak now", "सुन रही हूँ… बोलिए", "Sun rahi hoon… boliye")
            AssistantState.THINKING -> tr(style, "Thinking…", "सोच रही हूँ…", "Soch rahi hoon…")
            AssistantState.RUNNING -> tr(style, "Working on it. Press Stop anytime.", "काम कर रही हूँ। कभी भी रोकें।", "Kaam kar rahi hoon. Kabhi bhi Stop dabaiye.")
            AssistantState.IDLE -> null
        }
    }
    val endLabel = if (live != null) tr(style, "End conversation", "बातचीत बंद करें", "Baat band karein") else tr(style, "Stop", "रोको", "Stop")
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                    shape = RoundedCornerShape(20.dp),
                    // Stop reads as stop: soft red with red text, clear without being alarming.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (idle) MaterialTheme.colorScheme.primary else Danger50,
                        contentColor = if (idle) Color.White else Danger),
                    border = if (idle) null else BorderStroke(1.5.dp, Danger.copy(alpha = 0.4f)),
                ) {
                    Icon(if (idle) Icons.Filled.Mic else Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text(
                        if (idle) tr(style, "Talk to Saathi", "साथी से बात करें", "Saathi se baat karein") else endLabel,
                        style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
