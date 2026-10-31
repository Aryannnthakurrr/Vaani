package com.saathi.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
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

/** Screen frame with a large title and a labelled back button (64dp touch target). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaathiScaffold(
    title: String,
    onBack: (() -> Unit)?,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = { Column {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        } },
    ) { pad ->
        val base = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
        Column(
            modifier = if (scroll) base.verticalScroll(rememberScrollState()) else base,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            Row(Modifier.padding(bottom = 24.dp)) {}
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
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "press")
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = press,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(20.dp),
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
        Box(Modifier.size(120.dp).clip(CircleShape).background(Leaf50), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Leaf, modifier = Modifier.size(72.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
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
    val fg = readable(color)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp),
        color = if (filled) Color.White else fg,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.13f))
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
    border: Color = MaterialTheme.colorScheme.outline,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "press")
    Surface(
        onClick = onClick,
        interactionSource = press,
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = label },
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.5.dp, border),
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
