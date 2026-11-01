package com.saathi.app.ui

import com.saathi.app.ui.theme.Touch
import com.saathi.app.ui.theme.Space
import com.saathi.app.ui.theme.Border
import com.saathi.app.ui.theme.OnFill
import com.saathi.app.ui.theme.Paper
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantState
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.LiveState
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Edge
import com.saathi.app.ui.theme.Ink
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import com.saathi.app.ui.theme.Muted
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home (Stitch "3.1 Home"): the date and greeting with their Hindi lines, today's warnings, whether Scam Shield is
 * guarding the phone, the six actions as two-line tiles, History, and the talk button fixed at the bottom.
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
    fun a(en: String, hi: String) = alt(style, en, hi)

    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val alerts = events.count { it.ts >= dayAgo && (it.level == "red" || it.level == "amber") }
    val updates = messages.count { it.receivedAt >= weekAgo && it.category in USEFUL }

    // Scam Shield runs only with the messages-and-calls permissions; re-checked whenever Home comes back.
    val ctx = LocalContext.current
    val shieldStep = remember { Permissions.steps.first { it.id == "sms_calls" } }
    var shieldOn by remember { mutableStateOf(shieldStep.granted(ctx)) }
    LifecycleResumeEffect(Unit) {
        shieldOn = shieldStep.granted(ctx)
        onPauseOrDispose { }
    }

    val busy by c.assistant.taskActive.collectAsState()
    val live by c.assistant.live.collectAsState()
    val liveState = live?.state?.collectAsState()?.value
    val muted = live?.muted?.collectAsState()?.value ?: false
    val talking = live != null || state == AssistantState.LISTENING || state == AssistantState.THINKING
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MicBar(state, liveState, busy, talking, style, muted, canMute = live != null, onMute = { c.assistant.toggleMute() }) { c.assistant.onMicTapped() } }) { pad ->
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
                modifier = Modifier.padding(pad).padding(horizontal = Space.xxl),
            )
            return@AnimatedContent
        }
        // Scaffold already consumed the status-bar inset, so the header must use its top padding itself.
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())
            .verticalScroll(rememberScrollState()).padding(horizontal = Space.xl, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            HomeHeader(settings.userName, style) { navigate("settings") }

            TileRow {
                // Straight to the Doctor screen, which starts the conversation itself and shows it getting ready.
                Tile(s("Doctor", "डॉक्टर", "Doctor"), a("Doctor", "डॉक्टर"), Icons.Filled.MedicalServices,
                    { navigate("care") }, Modifier.weight(1f).riseIn(2))
                // Tasks ask their first question in a card; while one runs the other task tiles wait (Stop ends it).
                Tile(s("Cab", "कैब", "Cab"), a("Cab", "गाड़ी / कैब"), Icons.Filled.LocalTaxi,
                    { c.assistant.startTask(Tasks.UBER) }, Modifier.weight(1f).riseIn(3), enabled = !busy)
            }
            TileRow {
                Tile(s("House help", "घर की मदद", "House help"), a("House help", "सफ़ाई / मदद"), Icons.Filled.CleaningServices,
                    { c.assistant.startTask(Tasks.HOUSE_HELP) }, Modifier.weight(1f).riseIn(4), enabled = !busy)
                Tile(s("Updates", "अपडेट", "Updates"), a("Updates", "अपडेट्स"), Icons.Filled.Mail, { navigate("digest") },
                    Modifier.weight(1f).riseIn(5), badge = updates.takeIf { it > 0 }?.toString())
            }
            TileRow {
                Tile(s("Check item", "सामान जाँचें", "Saamaan jaanchein"), a("Check item", "सामान जाँचें"), Icons.Filled.PhotoCamera,
                    { navigate("camera") }, Modifier.weight(1f).riseIn(6))
                Tile(s("Teach me", "मुझे सिखाओ", "Sikhao"), a("Teach me", "मुझे सिखाएँ"), Icons.AutoMirrored.Filled.Help,
                    { c.assistant.startGuide(null) }, Modifier.weight(1f).riseIn(7), enabled = !busy)
            }

            // Safety status below the actions: today's warnings (hidden on a quiet day), then whether the scam shield
            // is really guarding messages and calls (the permissions), not a decoration.
            if (alerts > 0) {
                StatusCard(
                    icon = Icons.Filled.Shield, tint = Danger, bg = Danger50, iconBg = Danger.copy(alpha = 0.12f),
                    title = s("$alerts warnings today", "आज $alerts चेतावनी", "Aaj $alerts warnings"),
                    second = a("$alerts warnings today", "आज $alerts सुरक्षा चेतावनियाँ"),
                    titleColor = Danger, chevron = true, onClick = { navigate("alerts") },
                    modifier = Modifier.riseIn(8),
                )
            }
            if (shieldOn) StatusCard(
                icon = Icons.Filled.GppGood, tint = Leaf, bg = Leaf50, iconBg = OnFill,
                title = s("Vaani is keeping you safe", "वाणी आपकी सुरक्षा कर रही है", "Vaani aapko safe rakh rahi hai"),
                second = a("Vaani is keeping you safe", "वाणी आपकी सुरक्षा के लिए तैयार है"),
                titleColor = Ink, trailing = { StatusPill("LIVE", Leaf, filled = false, dot = true) },
                onClick = { navigate("alerts") }, modifier = Modifier.riseIn(9),
            ) else StatusCard(
                icon = Icons.Filled.GppMaybe, tint = Amber, bg = Amber50, iconBg = OnFill,
                title = s("Scam Shield is off. Tap to turn on", "धोखा-रक्षक बंद है। चालू करने के लिए दबाइए", "Scam Shield band hai. On karne ke liye dabaiye"),
                second = a("Scam Shield is off", "धोखा-रक्षक बंद है"),
                titleColor = Ink, chevron = true, onClick = { navigate("alerts") }, modifier = Modifier.riseIn(9),
            )

            // Rarely needed (mostly by family): a quiet link instead of a tile.
            val (press, pressMotion) = rememberPress()
            Surface(onClick = { navigate("log") }, interactionSource = press, color = Color.Transparent,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = Touch.min).then(pressMotion).riseIn(10)) {
                Row(Modifier.padding(horizontal = Space.md), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Icon(Icons.Filled.History, null, tint = Muted)
                    Text(s("History", "इतिहास", "History") + " / " + a("History", "पुराना इतिहास"),
                        style = MaterialTheme.typography.titleMedium, textDecoration = TextDecoration.Underline, color = Ink)
                }
            }
        }
        }
    }
}

private val USEFUL = setOf("bill_due", "subscription_renewal", "appointment", "delivery", "payment_done", "scam_suspected")

@Composable
private fun HomeHeader(name: String, style: ReplyStyle, onSettings: () -> Unit) {
    val hour = LocalTime.now().hour
    val ji = name.takeIf { it.isNotBlank() }
    val greeting = when {
        hour < 12 -> tr(style, "Good morning", "सुप्रभात", "Good morning")
        hour < 17 -> tr(style, "Good afternoon", "नमस्ते", "Namaste")
        else -> tr(style, "Good evening", "शुभ संध्या", "Good evening")
    } + ji?.let { ", $it ji" }.orEmpty() // no-break space: "ji" never sits alone on a line
    val greetingAlt = when {
        hour < 12 -> alt(style, "Good morning", "सुप्रभात")
        hour < 17 -> alt(style, "Good afternoon", "नमस्ते")
        else -> alt(style, "Good evening", "शुभ संध्या")
    } + ji?.let { if (style == ReplyStyle.HI) ", $it ji" else ", $it जी" }.orEmpty()
    val today = LocalDate.now()
    val hiDate = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("hi", "IN")))
    val enDate = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("en", "IN")))

    Row(verticalAlignment = Alignment.Top) {
        TwoLine(if (style == ReplyStyle.HI) hiDate else enDate, if (style == ReplyStyle.HI) enDate else hiDate, Modifier.weight(1f),
            mainStyle = MaterialTheme.typography.titleSmall, mainColor = Muted, secondColor = Muted)
        RoundIconButton(Icons.Outlined.Settings, tr(style, "Settings", "सेटिंग्स", "Settings"), onSettings)
    }
    Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
        Text(greeting, style = MaterialTheme.typography.headlineLarge, color = Ink)
        Text(greetingAlt, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A full-width status strip: icon circle, a title with its second-language line, then a chevron or a pill. */
@Composable
private fun StatusCard(
    icon: ImageVector, tint: Color, bg: Color, iconBg: Color, title: String, second: String, titleColor: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, chevron: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    val (press, pressMotion) = rememberPress()
    Surface(onClick = onClick, interactionSource = press, shape = MaterialTheme.shapes.medium, color = bg,
        border = BorderStroke(Border.control, tint.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth().then(pressMotion).semantics(mergeDescendants = true) { role = Role.Button }) {
        Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            IconBubble(icon, tint, iconBg, size = 52.dp, round = true)
            // A pill goes under the words, not beside them, so the title keeps the card's width.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                TwoLine(title, second, mainStyle = MaterialTheme.typography.titleMedium, mainColor = titleColor,
                    secondColor = if (titleColor == Danger) Danger else Leaf)
                trailing?.invoke()
            }
            if (chevron) Box(Modifier.size(40.dp).clip(CircleShape).background(Paper), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint)
            }
        }
    }
}

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    // Both tiles of a row take the taller one's height.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.lg), content = content)

/**
 * One action: a white outlined card, the icon on a pale teal square, the label and its second-language line.
 * A disabled tile fades back and ignores taps; a count sits in the top-right corner.
 */
@Composable
private fun Tile(
    label: String,
    second: String,
    icon: ImageVector,
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
        shape = MaterialTheme.shapes.medium,
        color = OnFill,
        border = BorderStroke(Border.control, Edge),
        modifier = modifier.fillMaxHeight().heightIn(min = 148.dp).then(pressMotion).alpha(if (enabled) 1f else 0.45f)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = listOfNotNull(label, badge).joinToString(", ")
            },
    ) {
        // Icon on top, words below across the whole tile: long Hinglish labels ("Saamaan jaanchein") wrap
        // between words instead of breaking inside one, even with the phone's large text.
        Column(Modifier.heightIn(min = 148.dp).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBubble(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 48.dp)
                Box(Modifier.weight(1f))
                badge?.let {
                    Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text(it, color = OnFill, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TwoLine(label, second, Modifier.fillMaxWidth(),
                mainStyle = MaterialTheme.typography.titleSmall, mainColor = Ink)
        }
    }
}

@Composable
private fun MicBar(state: AssistantState, live: LiveState?, busy: Boolean, talking: Boolean, style: ReplyStyle,
                   muted: Boolean, canMute: Boolean, onMute: () -> Unit, onClick: () -> Unit) {
    val idle = state == AssistantState.IDLE && !busy
    // While the talking view is up it already shows what is happening; the bar only adds a line while a task runs.
    val status = when {
        talking && muted -> tr(style, "Mic is muted. Vaani cannot hear you.", "माइक बंद है। वाणी आपको सुन नहीं सकती।", "Mic band hai. Vaani aapko sun nahi sakti.")
        talking -> null
        live == LiveState.CONNECTING -> tr(style, "Connecting…", "जुड़ रही हूँ…", "Connect ho rahi hoon…")
        state == AssistantState.RUNNING -> tr(style, "Working on it. Press Stop anytime.", "काम कर रही हूँ। कभी भी रोकें।", "Kaam kar rahi hoon. Kabhi bhi Stop dabaiye.")
        else -> null
    }
    // Same order as the tap itself: a running task is stopped first, then the conversation can be ended.
    val endLabel = if (live != null && !busy) tr(style, "End conversation", "बातचीत बंद करें", "Baat band karein") else tr(style, "Stop", "रोको", "Stop")
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = Border.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Muted,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
                }
                // Mute keeps the conversation open but stops sending the user's voice; End closes it.
                if (canMute) {
                    val (mutePress, muteMotion) = rememberPress()
                    Button(onClick = onMute, interactionSource = mutePress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Touch.button).then(muteMotion),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (muted) Ink else OnFill,
                            contentColor = if (muted) OnFill else Ink),
                        border = if (muted) null else BorderStroke(Border.control, Edge),
                    ) {
                        Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, null, modifier = Modifier.size(28.dp))
                        Text(
                            if (muted) tr(style, "Unmute", "माइक चालू करें", "Mic chalu karein") else tr(style, "Mute", "माइक बंद करें", "Mic band karein"),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Space.md))
                    }
                }
                val (press, pressMotion) = rememberPress()
                Button(
                    onClick = onClick,
                    interactionSource = press,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).then(pressMotion),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (idle) MaterialTheme.colorScheme.primary else Danger50,
                        contentColor = if (idle) OnFill else Danger),
                    border = if (idle) null else BorderStroke(Border.control, Danger.copy(alpha = 0.4f)),
                ) {
                    // The mic sits on a lighter disc, like the Stitch talk button.
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (idle) OnFill.copy(alpha = 0.2f) else Danger.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center) {
                        Icon(if (idle) Icons.Filled.Mic else Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.padding(start = Space.lg)) {
                        Text(if (idle) tr(style, "Talk to Vaani", "वाणी से बात करें", "Vaani se baat karein") else endLabel,
                            style = MaterialTheme.typography.titleMedium)
                        if (idle) Text(alt(style, "Talk to Vaani", "वाणी से बात करें"), style = MaterialTheme.typography.bodySmall,
                            color = OnFill.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}
