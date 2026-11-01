package com.saathi.app.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Pauses music and videos while Saathi speaks or listens, and lets them resume after. Without it YouTube kept
 * playing over Saathi's voice and the speech-to-text heard the video. Reference-counted, since speaking and
 * listening overlap (a question, then the answer).
 */
class AudioFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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

    suspend fun <T> during(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
