package com.saathi.app.features.care

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.sin

/**
 * The emergency siren (workflow A1): a tone sweeping 650 ↔ 1500 Hz every half second, synthesised once and looped on
 * the alarm stream so it sounds even in silent mode, plus a repeating vibration.
 */
class Siren(private val context: Context) {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val pcm = sweep()
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    play()
                }
        }.getOrNull()
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200), 0))
    }

    /** Quieter while the microphone listens for "I'm OK", so the siren doesn't drown the answer. */
    fun duck(on: Boolean) {
        track?.runCatching { setVolume(if (on) 0.15f else 1f) }
    }

    fun stop() {
        track?.runCatching { stop(); release() }
        track = null
        context.getSystemService(Vibrator::class.java)?.cancel()
    }

    /** One second: half a second rising 650 → 1500 Hz, half falling back, with continuous phase. */
    private fun sweep(): ShortArray {
        val out = ShortArray(RATE)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val f = if (t < 0.5) LOW + (HIGH - LOW) * (t / 0.5) else HIGH - (HIGH - LOW) * ((t - 0.5) / 0.5)
            phase += 2 * PI * f / RATE
            out[i] = (sin(phase) * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val RATE = 22_050
        const val LOW = 650.0
        const val HIGH = 1500.0
    }
}
