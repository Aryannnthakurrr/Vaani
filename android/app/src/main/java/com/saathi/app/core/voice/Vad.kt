package com.saathi.app.core.voice

import kotlin.math.sqrt

/**
 * Energy-based voice-activity detector (SPEC §6.6): speech ends after [silenceMs] of quiet once
 * speech has started; the turn is capped at [maxMs]. Pure, so it is unit-tested.
 */
class Vad(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 900,
    private val maxMs: Int = 30_000,
    private val noSpeechTimeoutMs: Int = 6_000,
) {
    private var heardSpeech = false
    private var silentSamples = 0
    private var totalSamples = 0
    private var noiseFloor = 300.0

    /** Short-term level 0..1 for the waveform caption. */
    var level: Float = 0f
        private set

    /** Feeds one PCM16 chunk; returns true when the turn is over. */
    fun feed(chunk: ShortArray, size: Int = chunk.size): Boolean {
        if (size <= 0) return false
        var sum = 0.0
        for (i in 0 until size) sum += chunk[i].toDouble() * chunk[i]
        val rms = sqrt(sum / size)
        level = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
        totalSamples += size
        val threshold = maxOf(700.0, noiseFloor * 2.5)
        if (rms > threshold) {
            heardSpeech = true
            silentSamples = 0
        } else {
            if (!heardSpeech) noiseFloor = noiseFloor * 0.95 + rms * 0.05
            silentSamples += size
        }
        val ms = { samples: Int -> samples * 1000L / sampleRate }
        return when {
            ms(totalSamples) >= maxMs -> true
            heardSpeech && ms(silentSamples) >= silenceMs -> true
            !heardSpeech && ms(totalSamples) >= noSpeechTimeoutMs -> true
            else -> false
        }
    }

    val speechDetected: Boolean get() = heardSpeech
}
