package com.saathi.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.voice.VoiceLevel

/**
 * Bars that move with the user's voice, under a spoken decision ("Shall I book?", "Send the ambulance?"), with a
 * short hint that a spoken answer works as well as the buttons.
 */
@Composable
fun VoiceWave(level: () -> Float, hint: String, color: Color, modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    var smooth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            smooth += (level() - smooth) * 0.35f
            phase += 0.25f
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth(0.7f).height(44.dp).clearAndSetSemantics { }) {
            val bars = VoiceLevel.bars(smooth, phase, BARS)
            val gap = size.width / (BARS * 2f)
            val mid = size.height / 2f
            bars.forEachIndexed { i, h ->
                val x = gap + i * 2 * gap
                val half = h * size.height / 2f
                // Thin, rounded bars: light enough to sit under any card without looking heavy.
                drawLine(color, Offset(x, mid - half), Offset(x, mid + half), strokeWidth = gap * 0.55f, cap = StrokeCap.Round)
            }
        }
        Text(hint, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val BARS = 13
