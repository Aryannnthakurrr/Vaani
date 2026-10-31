package com.saathi.app.core.voice

import kotlin.math.sqrt

/** Loudness and wave shapes for the voice animation. Pure, so it is JVM-tested. */
object VoiceLevel {
    /** RMS of [n] bytes of 16-bit little-endian mono PCM, scaled so normal speech sits around 0.3–0.8. */
    fun rms16(pcm: ByteArray, n: Int): Float {
        val samples = n / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
        }
        return (sqrt(sum / samples) / 6_000.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Heights (0–1) of [bars] wave bars for a [level] at animation [phase]: a calm ripple when quiet that swells
     * with the voice, highest in the middle.
     */
    fun bars(level: Float, phase: Float, bars: Int = 9): List<Float> = List(bars) { i ->
        val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / bars
        val ripple = 0.5f + 0.5f * kotlin.math.sin(phase + i * 0.9f)
        val l = level.coerceIn(0f, 1f)
        // Even in silence a soft wave travels along the bars, so a quiet listener never looks like a frozen loader.
        (0.10f + 0.08f * ripple * (1f - l) + l * centre * (0.55f + 0.45f * ripple)).coerceIn(0.08f, 1f)
    }
}
