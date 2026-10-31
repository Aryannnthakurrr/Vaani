package com.saathi.app.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.ReplyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

data class Heard(val transcript: String, val style: ReplyStyle, val engine: String)

/**
 * Turn-based listening (SPEC §6.6): record 16 kHz PCM with [Vad] → backend `/voice/stt` (Sarvam Saaras).
 * If the backend or Sarvam fails, times out (4 s) or is not configured, the same turn falls back to Android's
 * SpeechRecognizer, so callers never care which engine ran.
 */
class SpeechInput(
    private val context: Context,
    private val backend: () -> BackendApi,
    private val keyterms: () -> List<String>,
    private val preferredStyle: () -> ReplyStyle,
    /** Names sent to Sarvam as hints when a name is expected: family contacts and the user, never the phone book. */
    private val nameHints: () -> List<String> = { emptyList() },
    /** Every name the phone knows, phone book included; used only on the phone to fix near-miss names. */
    private val localNames: () -> List<String> = { emptyList() },
) {
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Set after a cloud failure so later turns skip straight to the on-device recognizer for a while. */
    @Volatile private var cloudDownUntil = 0L

    /** False when `/health` reports no Sarvam key: every turn then goes straight to the on-device recognizer. */
    @Volatile var cloudSpeech = true

    /**
     * @param cue beep first, so people who can't see the screen know when to start talking.
     * @param expectName the answer is a person's name: hint names only (app names like "Pronto" pulled names
     *   towards them), and fix the whole answer against known names. Otherwise only words in name positions
     *   ("send it to Keshu") are fixed.
     */
    suspend fun listen(cue: Boolean = false, expectName: Boolean = false): Heard? {
        val heard = listenRaw(cue, if (expectName) nameHints() else keyterms()) ?: return null
        val names = localNames()
        if (names.isEmpty()) return heard
        val fixed = if (expectName) NameMatcher.correctName(heard.transcript, names) else NameMatcher.correctInSentence(heard.transcript, names)
        return heard.copy(transcript = fixed)
    }

    private suspend fun listenRaw(cue: Boolean, hints: List<String>): Heard? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        if (cue) beep()
        _listening.value = true
        // Android 14+ silences background recording unless the foreground service is typed "microphone".
        com.saathi.app.core.overlay.OverlayService.start(context, com.saathi.app.core.overlay.OverlayService.ACTION_MIC_ON)
        var askedCloud = false
        try {
            if (cloudSpeech && System.currentTimeMillis() > cloudDownUntil) {
                askedCloud = true
                val pcm = record() ?: return null
                _listening.value = false // recorded: the mic is off while the words are transcribed
                val wav = File(context.cacheDir, "turn.wav")
                try {
                    withContext(Dispatchers.IO) { wav.writeBytes(WavWriter.wrap(pcm, SAMPLE_RATE)) }
                    val hint = when (preferredStyle()) { ReplyStyle.EN -> "en-IN"; ReplyStyle.HI -> "hi-IN"; else -> "unknown" }
                    val result = withTimeoutOrNull(4_000) { runCatching { backend().stt(wav, hint, hints) }.getOrNull() }
                    if (result != null && result.transcript.isNotBlank()) {
                        val style = result.replyStyle?.let(ReplyStyle::from) ?: LanguageStyle.detect(result.transcript, result.languageCode)
                        return Heard(result.transcript, style, "sarvam")
                    }
                    if (result == null) cloudDownUntil = System.currentTimeMillis() + 60_000
                    if (result != null) return null // cloud worked but heard nothing
                } finally {
                    wav.delete()
                }
            }
            // The cloud didn't answer: the words are listened to again, so say so with the beep.
            if (askedCloud) beep()
            _listening.value = true
            return onDevice()
        } finally {
            com.saathi.app.core.overlay.OverlayService.start(context, com.saathi.app.core.overlay.OverlayService.ACTION_MIC_OFF)
            _listening.value = false
            _level.value = 0f
        }
    }

    /** Short "your turn" tone on the media stream; it ends before recording starts so the Vad never hears it. */
    private suspend fun beep() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            delay(BEEP_MS + 120L)
        } finally {
            tone.release()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
        val vad = Vad(SAMPLE_RATE)
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(SAMPLE_RATE / 20) // 50 ms
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        try {
            rec.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                bytes.clear()
                for (i in 0 until n) bytes.putShort(chunk[i])
                out.write(bytes.array(), 0, n * 2)
                val over = vad.feed(chunk, n)
                _level.value = vad.level
                if (over) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (vad.speechDetected) out.toByteArray() else null
    }

    /** Android SpeechRecognizer fallback (hi-IN for Hindi/Hinglish users, en-IN otherwise). */
    private suspend fun onDevice(): Heard? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val lang = if (preferredStyle() == ReplyStyle.EN) "en-IN" else "hi-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        try {
            withTimeoutOrNull(32_000) {
                suspendCancellableCoroutine { cont ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (cont.isActive) cont.resume(text?.let { Heard(it, LanguageStyle.detect(it, lang), "android") })
                        }
                        override fun onError(error: Int) {
                            Log.d(TAG, "on-device STT error $error")
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onRmsChanged(rmsdB: Float) { _level.value = (rmsdB / 10f).coerceIn(0f, 1f) }
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    // Cancellation handlers can run on any thread; SpeechRecognizer must only be touched on main.
                    cont.invokeOnCancellation { mainHandler.post { runCatching { recognizer.cancel() } } }
                    recognizer.startListening(intent)
                }
            }
        } finally {
            mainHandler.post { runCatching { recognizer.destroy() } }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "SaathiSpeechIn"
        const val SAMPLE_RATE = 16_000
        private const val BEEP_MS = 150
        private const val BEEP_VOLUME = 70
    }
}

/** Minimal RIFF/WAVE header for 16-bit mono PCM. */
object WavWriter {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }
}
