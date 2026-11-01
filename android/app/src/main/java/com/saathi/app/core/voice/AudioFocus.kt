package com.saathi.app.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Quietens other apps while Saathi speaks or listens, and lets them carry on after. Without it YouTube kept playing
 * over Saathi's voice and the speech-to-text heard the video. Reference-counted, since lines and turns overlap.
 *
 * [exclusive]: pause the other app (listening: the recognizer must not hear the video). Otherwise the other app is
 * only turned down (speaking). Pausing while speaking made apps like Samsung Gallery redraw their screen on every
 * line, which guide mode took for the user's tap: it cut each instruction after a few words and said it again.
 */
class AudioFocus(context: Context, exclusive: Boolean) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(
        if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }
        .build()
    private var holders = 0

    @Synchronized fun acquire() {
        if (holders++ == 0) runCatching { am?.requestAudioFocus(request) }
    }

    @Synchronized fun release() {
        if (holders == 0) return
        if (--holders == 0) runCatching { am?.abandonAudioFocusRequest(request) }
    }
}
