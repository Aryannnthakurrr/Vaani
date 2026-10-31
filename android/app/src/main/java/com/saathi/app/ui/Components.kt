package com.saathi.app.ui

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.lerp
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
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        },
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
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
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
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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

/** Accent colours are tuned for light backgrounds; in dark mode lift them towards white so text stays readable. */
@Composable
fun readable(color: Color): Color = if (isSystemInDarkTheme()) lerp(color, Color.White, 0.45f) else color

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
