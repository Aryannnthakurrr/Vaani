package com.saathi.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The conversation screen: one calm circle that breathes while Saathi talks and swells with the user's voice, and
 * the last line said in large text. Nothing else, so the person can just talk.
 */
@Composable
fun TalkingView(
    level: () -> Float,
    saathiSpeaking: Boolean,
    status: String?,
    saathiLine: String,
    userLine: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var smooth by remember { mutableFloatStateOf(0f) }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(saathiSpeaking) {
        while (true) withFrameNanos {
            t += 0.06f
            // Saathi's own voice is not on the mic, so while it speaks the circle breathes on its own.
            val target = if (saathiSpeaking) 0.35f + 0.25f * sin(t * 2f) else level()
            smooth += (target - smooth) * 0.15f
        }
    }
    Column(modifier.fillMaxSize().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Canvas(Modifier.size(260.dp).clearAndSetSemantics { }) {
            val r = size.minDimension / 2f
            val core = r * (0.46f + 0.14f * smooth)
            // Soft halos that grow with the voice; the core stays solid so it never looks alarming.
            drawCircle(accent.copy(alpha = 0.08f), radius = core + r * (0.18f + 0.30f * smooth))
            drawCircle(accent.copy(alpha = 0.14f), radius = core + r * (0.08f + 0.16f * smooth))
            drawCircle(accent, radius = core)
        }
        status?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AnimatedContent(saathiLine, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "line") { line ->
            Text(line, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (userLine.isNotBlank()) {
            Text("“$userLine”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
    }
}
