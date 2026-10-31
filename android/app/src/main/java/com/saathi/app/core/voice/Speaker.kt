package com.saathi.app.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.privacy.PrivateSpan
import com.saathi.app.core.privacy.PrivateSpans
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Speech output (SPEC §6.6). Normal replies: Sarvam Bulbul via the backend, cached on disk; on any error
 * or timeout, Android TextToSpeech for that turn. Safety phrases: bundled clips or on-device TTS, never network.
 */
class Speaker(
    private val context: Context,
    private val backend: () -> BackendApi,
    private val phrases: Phrases,
    /** Looks up a vault token's value on the phone (private_values); never a network call. */
    private val privateValue: suspend (Long) -> PrivateSpan? = { null },
) {
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null

    private val _caption = MutableStateFlow("")
    /** Last spoken line; overlays show it in large text so every spoken response is also readable. */
    val caption: StateFlow<String> = _caption

    private val talking = java.util.concurrent.atomic.AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    /** True while any line is being spoken, so screens can animate a voice wave for Saathi too, not only the mic. */
    val speaking: StateFlow<Boolean> = _speaking

    private suspend fun <T> speakingWhile(block: suspend () -> T): T {
        _speaking.value = talking.incrementAndGet() > 0
        try {
            return block()
        } finally {
            _speaking.value = talking.decrementAndGet() > 0
        }
    }

    init {
        tts = TextToSpeech(context) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
    }

    /**
     * Speaks [text] and suspends until playback ends. Text with vault tokens (OTP, account, amounts) is spoken in
     * turns: ordinary words in the cloud voice, each private value by on-device TTS only, so it never leaves the
     * phone. The caption hides the values.
     */
    suspend fun say(text: String, style: ReplyStyle) {
        if (text.isBlank()) return
        speakingWhile {
            if (PrivateSpans.TOKEN.containsMatchIn(text)) return@speakingWhile sayWithPrivate(text, style)
            _caption.value = text
            sayPublic(text, style)
        }
    }

    private suspend fun sayWithPrivate(text: String, style: ReplyStyle) {
        _caption.value = PrivateSpans.TOKEN.replace(text, "••••")
        val hindi = style == ReplyStyle.HI
        for (part in PrivateSpans.parts(text)) {
            when (part) {
                is PrivateSpans.Part.Public -> sayPublic(part.text, style)
                is PrivateSpans.Part.Private -> {
                    val spoken = privateValue(part.id)?.let { PrivateSpans.spoken(it, hindi) } ?: PrivateSpans.missing(hindi)
                    speakOnDevice(spoken, style, SPEECH_USAGE)
                }
            }
        }
    }

    /** Speaks one vault value on-device only (the Live conversation's speak_private tool). */
    suspend fun sayPrivate(id: Long, style: ReplyStyle): Boolean {
        val span = privateValue(id)
        val hindi = style == ReplyStyle.HI
        speakOnDevice(span?.let { PrivateSpans.spoken(it, hindi) } ?: PrivateSpans.missing(hindi), style, SPEECH_USAGE)
        return span != null
    }

    /**
     * Sarvam returns a whole clip at once, so a long reply used to wait for all of it. Longer text is split into
     * sentences that are all requested together; the first plays as soon as it arrives while the rest finish.
     */
    private suspend fun sayPublic(text: String, style: ReplyStyle) = coroutineScope {
        val chunks = sentences(text)
        val clips = chunks.map { async { cloudAudio(it, style) } }
        chunks.forEachIndexed { i, chunk ->
            val audio = clips[i].await()
            if (audio == null || !playFile(audio, SPEECH_USAGE)) speakOnDevice(chunk, style, SPEECH_USAGE)
        }
    }

    /**
     * On-device voice only, no cloud round trip: for a question someone is waiting on, such as a hospital holding the
     * line for the patient's yes.
     */
    suspend fun sayQuick(text: String, style: ReplyStyle) {
        if (text.isBlank()) return
        speakingWhile {
            _caption.value = text
            speakOnDevice(text, style, SPEECH_USAGE)
        }
    }

    /** Speaks a safety phrase from the bundled table. Never touches the network. */
    suspend fun sayPhrase(key: String, style: ReplyStyle, inCall: Boolean = false) = speakingWhile { sayPhraseNow(key, style, inCall) }

    private suspend fun sayPhraseNow(key: String, style: ReplyStyle, inCall: Boolean) {
        val text = phrases.text(key, style)
        _caption.value = text
        val usage = if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else SPEECH_USAGE
        val res = phrases.clipRes(key, style)
        if (res != 0 && playRes(res, usage)) return
        speakOnDevice(text, style, usage)
    }

    /** In-call warning through the earpiece (USAGE_VOICE_COMMUNICATION). */
    suspend fun speakInCall(key: String, style: ReplyStyle) = sayPhrase(key, style, inCall = true)

    fun stop() {
        tts?.stop()
        player?.runCatching { stop(); release() }
        player = null
    }

    private suspend fun cloudAudio(text: String, style: ReplyStyle): File? {
        // The voice is part of the key: changing it in models.yaml must not replay audio cached in the old voice.
        val file = File(cacheDir, hash("$text|$style|aparna_hi_customer|1.1") + ".wav")
        if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
        // Bulbul takes ~1 s for a short line and longer for a whole digest, so the wait scales with length.
        val budgetMs = (4_000L + text.length * 15L).coerceAtMost(12_000L)
        val bytes = withTimeoutOrNull(budgetMs) { runCatching { backend().tts(text, style) }.getOrNull() } ?: return null
        if (bytes.size < 64) return null
        withContext(Dispatchers.IO) {
            file.writeBytes(bytes)
            trimCache()
        }
        return file
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private suspend fun playFile(file: File, usage: Int) = play(usage) { it.setDataSource(file.absolutePath) }

    private suspend fun playRes(res: Int, usage: Int) = play(usage) { mp ->
        context.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
    }

    private suspend fun play(usage: Int, source: (MediaPlayer) -> Unit): Boolean = withContext(Dispatchers.Main) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            source(mp)
            mp.prepare()
            player = mp
            withTimeoutOrNull(mp.duration.coerceAtLeast(0) + 3_000L) {
                suspendCancellableCoroutine { cont ->
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(true) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(false); true }
                    cont.invokeOnCancellation { runCatching { mp.stop() } }
                    mp.start()
                }
            } ?: true
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            false
        } finally {
            mp.release()
            if (player === mp) player = null
        }
    }

    private suspend fun speakOnDevice(text: String, style: ReplyStyle, usage: Int) {
        if (!ttsReady.await()) return
        val engine = tts ?: return
        val locale = if (style == ReplyStyle.EN) Locale("en", "IN") else Locale("hi", "IN")
        engine.language = locale
        engine.setSpeechRate(0.9f)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val id = UUID.randomUUID().toString()
        // Some TTS engines never call onDone (e.g. after an engine restart); never let that freeze a task.
        val budgetMs = (3_000L + text.length * 150L).coerceAtMost(45_000L)
        withTimeoutOrNull(budgetMs) { speakAndWait(engine, text, id) } ?: engine.stop()
    }

    private suspend fun speakAndWait(engine: TextToSpeech, text: String, id: String) {
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { engine.stop() }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        private val SENTENCE_END = Regex("(?<=[.!?।])\\s+")

        /** Sentences of at least ~40 characters (tiny ones join the next), so short replies stay one clip. */
        fun sentences(text: String): List<String> {
            val out = mutableListOf<String>()
            var current = ""
            for (piece in text.trim().split(SENTENCE_END)) {
                current = if (current.isEmpty()) piece else "$current $piece"
                if (current.length >= MIN_CHUNK) { out += current; current = "" }
            }
            if (current.isNotBlank()) if (out.isEmpty()) out += current else out[out.lastIndex] = out.last() + " " + current
            return out
        }

        private const val MIN_CHUNK = 40

        /**
         * Speech plays on the media volume. Samsung keeps a separate, hidden "assistant" volume that was at 1/15 on a
         * test phone, so USAGE_ASSISTANT made Saathi nearly silent while the media volume was at maximum.
         */
        const val SPEECH_USAGE = AudioAttributes.USAGE_MEDIA
        private const val TAG = "SaathiSpeaker"
        private const val CACHE_BYTES = 50L * 1024 * 1024
    }
}
