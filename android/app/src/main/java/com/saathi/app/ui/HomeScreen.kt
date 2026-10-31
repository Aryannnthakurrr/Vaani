package com.saathi.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantState
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.ui.theme.Accent
import com.saathi.app.ui.theme.Accent50
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Coral
import com.saathi.app.ui.theme.Coral50
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Ink
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

/**
 * A teal header with the date, the greeting and any warnings, then the actions as a grid of coloured tiles that
 * fits on one screen, and the talk button fixed at the bottom (SPEC §6.8).
 */
@Composable
fun HomeScreen(c: AppContainer, navigate: (String) -> Unit) {
    val settings by c.settings.flow.collectAsState()
    val state by c.assistant.state.collectAsState()
    val heard by c.assistant.lastHeard.collectAsState()
    val caption by c.assistant.screen.caption.collectAsState()
    val events by c.db.events().recent().collectAsState(initial = emptyList())
    val messages by c.db.messages().recent().collectAsState(initial = emptyList())
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val alerts = events.count { it.ts >= dayAgo && (it.level == "red" || it.level == "amber") }
    val updates = messages.count { it.receivedAt >= weekAgo && it.category in USEFUL }

    val busy by c.assistant.taskActive.collectAsState()
    val live by c.assistant.live.collectAsState()
    val liveState = live?.state?.collectAsState()?.value
    val talking = live != null || state == AssistantState.LISTENING || state == AssistantState.THINKING
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MicBar(state, liveState, busy, talking, style) { c.assistant.onMicTapped() } }) { pad ->
        // Talking and the tiles cross-fade, with a slight zoom, instead of jumping.
        AnimatedContent(talking, label = "talk",
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(180)) },
        ) { isTalking ->
        if (isTalking) {
            val liveLines = live?.transcripts?.collectAsState()?.value.orEmpty()
            TalkingView(
                level = { c.assistant.voiceLevel() },
                saathiSpeaking = liveState == LiveState.SPEAKING,
                status = null, // the bar below already says listening / speaking
                // In a live conversation only its own transcript counts; otherwise the current run's caption (never
                // an older spoken line).
                saathiLine = liveLines.lastOrNull { it.role == "saathi" }?.text ?: (if (live == null) caption?.text.orEmpty() else "").ifBlank {
                    s("I'm listening. Tell me what you need.", "मैं सुन रही हूँ। बताइए क्या चाहिए।", "Main sun rahi hoon. Bataiye kya chahiye.")
                },
                userLine = liveLines.lastOrNull { it.role == "user" }?.text ?: heard,
                modifier = Modifier.padding(pad).padding(horizontal = 24.dp),
            )
            return@AnimatedContent
        }
        Column(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()).verticalScroll(rememberScrollState())) {
            HomeHeader(settings.userName, alerts, style, onSettings = { navigate("settings") }, onAlerts = { navigate("alerts") })

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TileRow {
                    // Straight to the Doctor screen, which starts the conversation itself and shows it getting ready.
                    Tile(s("Doctor", "डॉक्टर", "Doctor"), Icons.Outlined.MedicalServices, Accent, Accent50, { navigate("care") },
                        Modifier.weight(1f).riseIn(0))
                    // Tasks ask their first question in a card; while one runs the other task tiles wait (Stop ends it).
                    Tile(s("Cab", "कैब", "Cab"), Icons.Outlined.LocalTaxi, Amber, Amber50, { c.assistant.startTask(Tasks.UBER) },
                        Modifier.weight(1f).riseIn(1), enabled = !busy)
                }
                TileRow {
                    Tile(s("House help", "घर की मदद", "House help"), Icons.Outlined.CleaningServices, Leaf, Leaf50,
                        { c.assistant.startTask(Tasks.HOUSE_HELP) }, Modifier.weight(1f).riseIn(2), enabled = !busy)
                    Tile(s("Updates", "अपडेट", "Updates"), Icons.Outlined.MailOutline, Sky, Sky50, { navigate("digest") },
                        Modifier.weight(1f).riseIn(3), badge = updates.takeIf { it > 0 }?.toString())
                }
                TileRow {
                    Tile(s("Check product", "सामान जाँचें", "Saamaan jaanchein"), Icons.Outlined.PhotoCamera, Coral, Coral50,
                        { navigate("camera") }, Modifier.weight(1f).riseIn(4))
                    Tile(s("Teach me", "सिखाओ", "Sikhao"), Icons.AutoMirrored.Outlined.HelpOutline, Ink, Soft,
                        { c.assistant.startGuide(null) }, Modifier.weight(1f).riseIn(5), enabled = !busy)
                }
                // Rarely needed (mostly by family): a quiet link instead of a tile.
                TextButton({ navigate("log") }, Modifier.fillMaxWidth().heightIn(min = 56.dp).riseIn(6)) {
                    Text(s("History", "इतिहास", "History"), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        }
    }
}

private val USEFUL = setOf("bill_due", "subscription_renewal", "appointment", "delivery", "payment_done", "scam_suspected")

@Composable
private fun HomeHeader(name: String, alerts: Int, style: ReplyStyle, onSettings: () -> Unit, onAlerts: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> tr(style, "Good morning", "सुप्रभात", "Good morning")
        hour < 17 -> tr(style, "Good afternoon", "नमस्ते", "Namaste")
        else -> tr(style, "Good evening", "शुभ संध्या", "Good evening")
    } + name.takeIf { it.isNotBlank() }?.let { ", $it ji" }.orEmpty()
    val locale = if (style == ReplyStyle.HI) Locale("hi", "IN") else Locale("en", "IN")
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))

    HeaderBand {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(date, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
                Text(greeting, style = MaterialTheme.typography.headlineLarge, color = Color.White, modifier = Modifier.semantics { heading() })
            }
            RoundIconButton(Icons.Outlined.Settings, tr(style, "Settings", "सेटिंग्स", "Settings"), onSettings)
        }
        // Warnings live in the header, where they are seen first, instead of as one more tile.
        if (alerts > 0) {
            val (press, pressMotion) = rememberPress()
            Surface(onClick = onAlerts, interactionSource = press, shape = RoundedCornerShape(20.dp), color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).then(pressMotion)
                    .semantics(mergeDescendants = true) { role = Role.Button }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).background(Danger50, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Shield, null, tint = Danger, modifier = Modifier.size(28.dp))
                    }
                    Text(tr(style, "$alerts warnings today", "आज $alerts चेतावनी", "Aaj $alerts warnings"),
                        style = MaterialTheme.typography.titleLarge, color = Danger, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Danger)
                }
            }
        } else {
            Box(Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)

/**
 * One action as a soft coloured tile: a white icon disc on top, the label below. Each action has its own colour, so
 * they are told apart at a glance. A disabled tile fades back and ignores taps.
 */
@Composable
private fun Tile(
    label: String,
    icon: ImageVector,
    tint: Color,
    tintBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = press,
        shape = RoundedCornerShape(26.dp),
        color = tintBg,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        modifier = modifier.height(100.dp).then(pressMotion).alpha(if (enabled) 1f else 0.45f)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = listOfNotNull(label, badge).joinToString(", ")
            },
    ) {
        // Icon beside the label keeps three rows of tiles on one screen even with the phone's large text.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The count sits on the icon's corner, like a phone's app badge, so it never covers the label.
                Box {
                    Box(Modifier.size(44.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    badge?.let {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).background(tint, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 1.dp)) {
                            Text(it, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = Ink, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MicBar(state: AssistantState, live: LiveState?, busy: Boolean, talking: Boolean, style: ReplyStyle, onClick: () -> Unit) {
    val idle = state == AssistantState.IDLE && !busy
    // While the talking view is up it already shows what is happening; the bar only adds a line while a task runs.
    val status = when {
        talking -> null
        live == LiveState.CONNECTING -> tr(style, "Connecting…", "जुड़ रही हूँ…", "Connect ho rahi hoon…")
        state == AssistantState.RUNNING -> tr(style, "Working on it. Press Stop anytime.", "काम कर रही हूँ। कभी भी रोकें।", "Kaam kar rahi hoon. Kabhi bhi Stop dabaiye.")
        else -> null
    }
    // Same order as the tap itself: a running task is stopped first, then the conversation can be ended.
    val endLabel = if (live != null && !busy) tr(style, "End conversation", "बातचीत बंद करें", "Baat band karein") else tr(style, "Stop", "रोको", "Stop")
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = Muted,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
                }
                val (press, pressMotion) = rememberPress()
                Button(
                    onClick = onClick,
                    interactionSource = press,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).then(pressMotion),
                    shape = RoundedCornerShape(24.dp),
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
