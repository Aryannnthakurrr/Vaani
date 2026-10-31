package com.saathi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantState
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Saffron
import com.saathi.app.ui.theme.SaffronDark
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Tile(val emoji: String, val label: String, val accent: Color, val onClick: () -> Unit)

/** Hero header with live status, six huge colour-coded tiles, and a fixed mic bar (SPEC §6.8). */
@Composable
fun HomeScreen(c: AppContainer, navigate: (String) -> Unit) {
    val settings by c.settings.flow.collectAsState()
    val state by c.assistant.state.collectAsState()
    val heard by c.assistant.lastHeard.collectAsState()
    val caption by c.speaker.caption.collectAsState()
    val events by c.db.events().recent().collectAsState(initial = emptyList())
    val messages by c.db.messages().recent().collectAsState(initial = emptyList())
    val style = settings.replyStyle

    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val alerts = events.count { it.ts >= dayAgo && (it.level == "red" || it.level == "amber") }
    val updates = messages.count { it.receivedAt >= weekAgo && it.category in USEFUL }

    val tiles = listOf(
        Tile("🚕", tr(style, "Book a cab", "कैब बुक करें", "Cab book karein"), Color(0xFFE8A200)) { c.assistant.startTask(Tasks.UBER) },
        Tile("🏠", tr(style, "House help", "घर की सफ़ाई", "House help"), Color(0xFF00897B)) { c.assistant.startTask(Tasks.HOUSE_HELP) },
        Tile("🛒", tr(style, "Shopping camera", "सामान जाँचें", "Shopping camera"), Color(0xFF2E7D32)) { navigate("camera") },
        Tile("🩺", tr(style, "Doctor", "डॉक्टर", "Doctor"), Color(0xFF1E63C8)) { c.assistant.startTask(Tasks.PRACTO) },
        Tile("📬", tr(style, "My updates", "मेरे अपडेट", "Mere updates"), Color(0xFF6A4BC4)) { navigate("digest") },
        Tile("❓", tr(style, "Show me how", "मुझे सिखाओ", "Mujhe sikhao"), Saffron) { c.assistant.startGuide(null) },
    )

    val live by c.assistant.live.collectAsState()
    val liveState = live?.state?.collectAsState()?.value
    Scaffold(bottomBar = { MicBar(state, liveState, style) { c.assistant.onMicTapped() } }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hero(settings.userName, style, alerts, updates, onSettings = { navigate("settings") },
                onAlerts = { navigate("alerts") }, onUpdates = { navigate("digest") })

            Text(tr(style, "What would you like to do?", "आप क्या करना चाहेंगे?", "Aap kya karna chahenge?"),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { row.forEach { BigTile(it, Modifier.weight(1f)) } }
            }

            if (heard.isNotBlank()) InfoCard(title = "“$heard”", badge = tr(style, "You said", "आपने कहा", "Aapne kaha"), icon = "🗣️")
            // In a live conversation Saathi's words come from the transcript, not the turn-based speaker.
            val liveLines = live?.transcripts?.collectAsState()?.value.orEmpty()
            val saathiLine = liveLines.lastOrNull { it.role == "saathi" }?.text ?: caption
            if (saathiLine.isNotBlank()) {
                InfoCard(title = saathiLine, badge = "Saathi", icon = "💬", accent = Saffron,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                QuickLink("📝", tr(style, "Activity", "गतिविधि", "Activity"), Modifier.weight(1f)) { navigate("log") }
                QuickLink("🛡️", tr(style, "Alerts", "चेतावनी", "Alerts"), Modifier.weight(1f)) { navigate("alerts") }
                QuickLink("⚙️", tr(style, "Settings", "सेटिंग्स", "Settings"), Modifier.weight(1f)) { navigate("settings") }
            }
        }
    }
}

private val USEFUL = setOf("bill_due", "subscription_renewal", "appointment", "delivery", "payment_done", "scam_suspected")

@Composable
private fun Hero(
    name: String, style: ReplyStyle, alerts: Int, updates: Int,
    onSettings: () -> Unit, onAlerts: () -> Unit, onUpdates: () -> Unit,
) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> tr(style, "Good morning", "सुप्रभात", "Good morning")
        hour < 17 -> tr(style, "Good afternoon", "नमस्ते", "Namaste")
        else -> tr(style, "Good evening", "शुभ संध्या", "Good evening")
    } + name.takeIf { it.isNotBlank() }?.let { ", $it ji" }.orEmpty()
    val locale = if (style == ReplyStyle.HI) Locale("hi", "IN") else Locale("en", "IN")
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))

    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Saffron, SaffronDark))).padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(date, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
                    Text(greeting, style = MaterialTheme.typography.headlineLarge, color = Color.White,
                        modifier = Modifier.semantics { heading() })
                }
                Surface(onClick = onSettings, shape = CircleShape, color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(64.dp).semantics { contentDescription = "Settings" }) {
                    Box(contentAlignment = Alignment.Center) { Text("⚙️", fontSize = 28.sp) }
                }
            }
            Text(tr(style, "Tap a big button, or tap the microphone and talk to me.", "कोई बड़ा बटन दबाइए, या माइक दबाकर मुझसे बात कीजिए।",
                "Koi bada button dabaiye, ya mic dabakar mujhse baat kijiye."), style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(if (alerts > 0) "⚠️ " + tr(style, "$alerts alerts today", "आज $alerts चेतावनी", "Aaj $alerts alerts")
                    else "🛡️ " + tr(style, "Scam Shield on", "धोखा-रक्षक चालू", "Scam Shield on"), alert = alerts > 0, onClick = onAlerts)
                if (updates > 0) HeroChip("📬 " + tr(style, "$updates updates", "$updates अपडेट", "$updates updates"), onClick = onUpdates)
            }
        }
    }
}

@Composable
private fun HeroChip(text: String, alert: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
        color = if (alert) Danger else SaffronDark,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun BigTile(t: Tile, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val bg = t.accent.copy(alpha = if (isSystemInDarkTheme()) 0.28f else 0.16f).compositeOver(surface)
    Card(
        onClick = t.onClick,
        modifier = modifier.aspectRatio(1.05f).heightIn(min = 140.dp).semantics { role = Role.Button; contentDescription = t.label },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(surface), contentAlignment = Alignment.Center) {
                Text(t.emoji, fontSize = 42.sp)
            }
            Text(t.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun MicBar(state: AssistantState, live: LiveState?, style: ReplyStyle, onClick: () -> Unit) {
    val idle = state == AssistantState.IDLE
    val status = when {
        live == LiveState.CONNECTING -> tr(style, "Connecting…", "जुड़ रहा हूँ…", "Connect ho raha hoon…")
        live == LiveState.SPEAKING -> tr(style, "Saathi is speaking. You can interrupt.", "साथी बोल रहा है। आप बीच में बोल सकते हैं।",
            "Saathi bol raha hai. Aap beech mein bol sakte hain.")
        live == LiveState.LISTENING -> tr(style, "Talk freely. I'm listening.", "बेझिझक बोलिए। मैं सुन रहा हूँ।", "Aaram se boliye. Main sun raha hoon.")
        else -> when (state) {
        AssistantState.LISTENING -> tr(style, "Listening… speak now", "सुन रहा हूँ… बोलिए", "Sun raha hoon… boliye")
        AssistantState.THINKING -> tr(style, "Thinking…", "सोच रहा हूँ…", "Soch raha hoon…")
        AssistantState.RUNNING -> tr(style, "Working on it. Press Stop anytime.", "काम कर रहा हूँ। कभी भी रोकें।", "Kaam kar raha hoon. Kabhi bhi Stop dabaiye.")
        AssistantState.IDLE -> null
        }
    }
    val endLabel = if (live != null) tr(style, "  End conversation", "  बातचीत बंद करें", "  Baat band karein") else tr(style, "  Stop", "  रोको", "  Stop")
    Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            status?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
            }
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                shape = RoundedCornerShape(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (idle) Saffron else Danger, contentColor = Color.White),
            ) {
                Text(if (idle) "🎤" else "■", fontSize = 30.sp)
                Text(
                    if (idle) tr(style, "  Talk to Saathi", "  साथी से बात करें", "  Saathi se baat karein") else endLabel,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}

@Composable
private fun QuickLink(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 22.sp)
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), maxLines = 1)
        }
    }
}
