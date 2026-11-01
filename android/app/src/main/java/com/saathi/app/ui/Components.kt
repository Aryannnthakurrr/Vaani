package com.saathi.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Screen frame: a plain header (round back button, title, and the title in the other language beneath it), then
 * the screen's content on the warm page. [bottomBar] stays fixed above the system bar (Onboarding's Start).
 */
@Composable
fun SaathiScaffold(
    title: String,
    onBack: (() -> Unit)?,
    scroll: Boolean = true,
    subtitle: String? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { HeaderBand { HeaderBar(title, subtitle, onBack) } },
        bottomBar = {
            bottomBar?.let {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { it() }
                    }
                }
            }
        },
    ) { pad ->
        val base = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)
        Column(
            modifier = (if (scroll) base.verticalScroll(rememberScrollState()) else base)
                .then(if (bottomBar == null) Modifier.navigationBarsPadding() else Modifier),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.padding(top = 4.dp))
            content()
            Row(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

/** The top of a screen: plain page colour under the status bar, dark title; no coloured block. */
@Composable
fun HeaderBand(modifier: Modifier = Modifier, statusBarInset: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().then(if (statusBarInset) Modifier.statusBarsPadding() else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), content = content)
}

@Composable
private fun HeaderBar(title: String, subtitle: String?, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val style = com.saathi.app.SaathiApp.container.settings.flow.collectAsState().value.replyStyle
        if (onBack != null) RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr(style, "Back", "वापस", "Wapas"), onBack)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            subtitle?.let { Text(it, fontSize = 17.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * The same words in the user's second script, shown smaller under the main line (as in the Stitch designs):
 * Hindi under English and Hinglish, English under Hindi.
 */
fun alt(style: ReplyStyle, en: String, hi: String): String = if (style == ReplyStyle.HI) en else hi

/** A line of text with its [alt] translation beneath it. */
@Composable
fun TwoLine(
    main: String,
    second: String?,
    modifier: Modifier = Modifier,
    mainStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    secondColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    secondSize: androidx.compose.ui.unit.TextUnit = 17.sp,
    mainColor: Color = Color.Unspecified,
    align: TextAlign? = null,
) {
    Column(modifier) {
        Text(main, style = mainStyle, color = mainColor, textAlign = align)
        if (!second.isNullOrBlank() && second != main) {
            Text(second, fontSize = secondSize, lineHeight = secondSize * 1.35f, color = secondColor, textAlign = align,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}

/** An icon on a soft tinted circle (or rounded square), the leading mark of cards and rows. */
@Composable
fun IconBubble(icon: ImageVector, tint: Color, bg: Color, size: androidx.compose.ui.unit.Dp = 48.dp, round: Boolean = false) {
    Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/**
 * One group of a screen (Settings sections, the Scam Shield status): a white card with a clear outline, an icon
 * square, a heading with a one-line description, then its content.
 */
@Composable
fun SectionCard(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    tintBg: Color = MaterialTheme.colorScheme.primaryContainer,
    container: Color = Color.White,
    border: Color = com.saathi.app.ui.theme.Edge,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(shape = RoundedCornerShape(16.dp), color = container, border = BorderStroke(1.5.dp, border), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                icon?.let { IconBubble(it, tint, tintBg) }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, fontSize = 17.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A filled status label with an optional leading icon ("DANGER • खतरा", "● LIVE"). */
@Composable
fun StatusPill(text: String, color: Color, filled: Boolean = true, icon: ImageVector? = null, dot: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.12f))
            .then(if (filled) Modifier else Modifier.border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (filled) Color.White else color
        if (dot) Box(Modifier.size(9.dp).clip(CircleShape).background(fg))
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg)
    }
}

/** A full-width button with a leading icon and an optional second-language line (Stitch's primary CTA). */
@Composable
fun IconButtonBig(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    second: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = Color.White,
    border: Color? = null,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val (press, pressMotion) = rememberPress()
    Button(
        onClick = onClick, enabled = enabled, interactionSource = press,
        modifier = modifier.fillMaxWidth().heightIn(min = height).then(pressMotion),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
        border = border?.let { BorderStroke(1.5.dp, it) },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        icon?.let { Icon(it, null, modifier = Modifier.size(26.dp)) }
        Column(Modifier.padding(start = if (icon != null) 12.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (!second.isNullOrBlank() && second != text) Text(second, fontSize = 16.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center, color = contentColor.copy(alpha = 0.85f))
        }
    }
}

/** A round icon button for the top of a screen (back, settings): 48dp, white with a hairline border. */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val (press, pressMotion) = rememberPress()
    Surface(onClick = onClick, interactionSource = press, shape = CircleShape, color = Color.White,
        border = BorderStroke(1.5.dp, com.saathi.app.ui.theme.Edge),
        modifier = Modifier.size(52.dp).then(pressMotion).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
        }
    }
}

/** A full-width button with at least a 64dp touch target and large label. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
) {
    val (press, pressMotion) = rememberPress()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = press,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).then(pressMotion),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
}

/** The calm second choice next to a [BigButton] ("No", "Other times"): soft grey, dark text, same size. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    BigButton(text, onClick, modifier, color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)

/** The end of a task: a large green tick, one sentence, and an optional small line ("Added to calendar"). */
@Composable
fun SuccessView(title: String, line: String, note: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DrawnTick(Modifier.size(120.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** A green circle that pops in on a soft spring, then a tick that draws itself: the "done" moment. */
@Composable
private fun DrawnTick(modifier: Modifier) {
    val circle = remember { Animatable(0.6f) }
    val stroke = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        circle.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        stroke.animateTo(1f, tween(420, easing = Arrive))
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val r = size.minDimension / 2f * circle.value
        drawCircle(Leaf50, radius = r)
        val w = size.width
        val tick = Path().apply {
            moveTo(w * 0.30f, w * 0.52f); lineTo(w * 0.45f, w * 0.66f); lineTo(w * 0.71f, w * 0.38f)
        }
        val part = Path()
        PathMeasure().apply { setPath(tick, false); getSegment(0f, length * stroke.value, part, true) }
        drawPath(part, Leaf, style = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A large information card. [accent] colours the left stripe, the badge pill and the icon bubble
 * (e.g. red for scams); [icon] is an emoji shown in a tinted circle.
 */
@Composable
fun InfoCard(
    title: String,
    body: String? = null,
    accent: Color? = null,
    badge: String? = null,
    modifier: Modifier = Modifier,
    icon: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(badge, title, body).joinToString(". ")
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // The accent stripe is drawn, not laid out: an intrinsic-height row mis-measures Devanagari text.
        Row(Modifier.drawBehind { if (accent != null) drawRect(accent, size = Size(8.dp.toPx(), size.height)) }) {
            Row(Modifier.padding(start = if (accent != null) 26.dp else 18.dp, top = 18.dp, end = 18.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (icon != null) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(icon, fontSize = 26.sp) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (badge != null) Pill(badge, tint)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                    if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    extra()
                }
            }
        }
    }
}

/** Small rounded label, e.g. a category or status. */
@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    val shade by animateColorAsState(color, tween(400), label = "pill")
    val fg = readable(shade)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp),
        color = if (filled) Color.White else fg,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) shade else shade.copy(alpha = 0.13f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Accent colours are tuned for the white theme (the app is always light), so they are used as they are. */
@Composable
fun readable(color: Color): Color = color

/**
 * One large action per row (Home and menus): a white card with an icon on a soft tinted tile and a few words.
 * The tint is the only colour, so actions are easy to tell apart without anything loud. At least 96dp tall.
 */
@Composable
fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    tintBg: Color = MaterialTheme.colorScheme.primaryContainer,
    border: Color = com.saathi.app.ui.theme.Edge,
) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = onClick,
        interactionSource = press,
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp).then(pressMotion)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, border),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(tintBg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
            }
            Text(label, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** "2 hours ago" / "2 घंटे पहले" for list rows. */
fun relativeTime(ts: Long, style: ReplyStyle, now: Long = System.currentTimeMillis()): String {
    val min = ((now - ts) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> tr(style, "just now", "अभी", "abhi")
        min < 60 -> tr(style, "$min min ago", "$min मिनट पहले", "$min minute pehle")
        min < 24 * 60 -> (min / 60).let { tr(style, "$it hr ago", "$it घंटे पहले", "$it ghante pehle") }
        else -> (min / (24 * 60)).let { tr(style, "$it days ago", "$it दिन पहले", "$it din pehle") }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { heading() })
}

@Composable
fun EmptyNote(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
