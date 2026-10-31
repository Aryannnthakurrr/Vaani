package com.saathi.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateListOf
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
    // Ripples: (start time, strength) of rings that grow out of the circle and fade: strong every ~0.7 s while
    // someone talks, faint every ~2.2 s while Saathi waits, so "I'm listening" is visible even in silence.
    val ripples = remember { mutableStateListOf<Pair<Float, Float>>() }
    LaunchedEffect(saathiSpeaking) {
        var last = 0L
        var lastRipple = -1f
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1e9f
            last = now
            t += dt
            // Saathi's own voice is not on the mic, so while it speaks the level follows a soft speech-like wave.
            val target = if (saathiSpeaking) 0.45f + 0.2f * sin(t * 5f) * sin(t * 1.3f) else level()
            // Rises quickly with a voice, falls back slowly: feels like breathing rather than flickering.
            smooth += (target - smooth) * (if (target > smooth) 0.25f else 0.06f)
            val talking = saathiSpeaking || smooth > 0.12f
            if (t - lastRipple > if (talking) 0.7f else 2.2f) { ripples += t to (if (talking) 0.22f else 0.12f); lastRipple = t }
            ripples.removeAll { t - it.first > RIPPLE_S }
        }
    }
    Column(modifier.fillMaxSize().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Canvas(Modifier.size(280.dp).clearAndSetSemantics { }) {
            val r = size.minDimension / 2f
            // Quiet: a slow breath. Talking: the circle swells with the voice.
            val breath = 0.035f * sin(t * 1.3f)
            val core = r * (0.44f + breath + 0.12f * smooth)
            for ((start, strength) in ripples) {
                val p = ((t - start) / RIPPLE_S).coerceIn(0f, 1f)
                val eased = 1f - (1f - p) * (1f - p)
                drawCircle(accent.copy(alpha = strength * (1f - p)), radius = core + (r - core) * eased)
            }
            drawCircle(accent.copy(alpha = 0.10f), radius = core + r * (0.06f + 0.10f * smooth))
            drawCircle(accent, radius = core)
        }
        status?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // The live transcript grows a few words at a time: the text box grows smoothly instead of fading per word.
        Text(saathiLine, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(220)).semantics { liveRegion = LiveRegionMode.Polite })
        if (userLine.isNotBlank()) {
            Text("“$userLine”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
    }
}

private const val RIPPLE_S = 1.6f
