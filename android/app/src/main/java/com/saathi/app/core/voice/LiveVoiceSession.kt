package com.saathi.app.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Runs the tools Gemini Live asks for (docs/LEARNING.md §2) and returns a JSON result for the model. */
fun interface ToolHandler {
    suspend fun handle(name: String, args: JsonObject): JsonElement
}

enum class LiveState { CONNECTING, LISTENING, SPEAKING, PAUSED, ENDED, ERROR }

/**
 * A realtime spoken conversation with Saathi over `WS /live/voice` (backend relays to Gemini Live).
 * Mic audio streams up as 16 kHz PCM16 in 50 ms chunks; Saathi's 24 kHz PCM plays as it arrives. The user can
 * talk over Saathi at any time: the server answers with `interrupted` and queued playback is dropped.
 */
class LiveVoiceSession(
    private val context: Context,
    private val url: String,
    private val replyStyle: () -> String,
    private val tools: ToolHandler,
    private val onEnded: (connected: Boolean) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var micJob: Job? = null
    private var player: AudioTrack? = null
    private val playQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptLog = TranscriptLog()

    @Volatile private var connected = false
    @Volatile private var paused = false
    @Volatile private var lastAudioAt = 0L

    private val _state = MutableStateFlow(LiveState.CONNECTING)
    val state: StateFlow<LiveState> = _state

    private val _transcripts = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcripts: StateFlow<List<TranscriptLine>> = _transcripts

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("no microphone permission")
            return
        }
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                webSocket.send(LiveWire.config(replyStyle()))
                _state.value = LiveState.LISTENING
                startPlayer()
                startMic()
                startSpeakingWatch()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LiveWire.parse(text)?.let(::onEvent)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "live voice socket failed: ${t.message}")
                fail(t.message ?: "connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()
        })
    }

    private fun onEvent(e: LiveEvent) {
        when (e) {
            is LiveEvent.Audio -> {
                if (paused) return
                lastAudioAt = System.currentTimeMillis()
                _state.value = LiveState.SPEAKING
                playQueue.trySend(e.pcm)
            }
            is LiveEvent.Transcript -> {
                // A line from the other speaker means the previous turn is over.
                val last = _transcripts.value.lastOrNull()
                if (last != null && last.role != e.role) transcriptLog.closeOpen()
                _transcripts.value = transcriptLog.apply(e)
            }
            is LiveEvent.Tool -> scope.launch {
                val result = runCatching { tools.handle(e.name, e.args) }.getOrElse { err ->
                    buildJsonObject { put("error", JsonPrimitive(err.message ?: "tool failed")) }
                }
                ws?.send(LiveWire.toolResult(e.id, result))
            }
            LiveEvent.Interrupted -> flushPlayback()
            is LiveEvent.Error -> Log.w(TAG, "live voice error: ${e.message}")
        }
    }

    /** Typed input: works exactly like speaking (for users who cannot speak, and for task-outcome notes). */
    fun sendText(text: String) {
        ws?.send(LiveWire.text(text))
    }

    /** Stops the mic and silences Saathi while a task runs (its own confirm/ask turns use the turn-based path). */
    fun pause() {
        paused = true
        micJob?.cancel()
        micJob = null
        flushPlayback()
        if (_state.value != LiveState.ENDED) _state.value = LiveState.PAUSED
    }

    fun resume() {
        if (!connected || _state.value == LiveState.ENDED) return
        paused = false
        _state.value = LiveState.LISTENING
        startMic()
    }

    fun end() {
        runCatching { ws?.send(LiveWire.end()) }
        ws?.close(1000, "bye")
        finish()
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (micJob?.isActive == true) return
        micJob = scope.launch {
            val rate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                fail("microphone unavailable")
                return@launch
            }
            // Echo cancellation keeps Saathi's own voice (from the speaker) out of the mic.
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            val chunk = ByteArray(rate / 20 * 2) // 50 ms of 16-bit mono
            try {
                rec.startRecording()
                while (isActive && !paused) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n > 0) ws?.send(LiveWire.audio(chunk, n))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                aec?.release()
                ns?.release()
            }
        }
    }

    private fun startPlayer() {
        val rate = 24_000
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .build()
        track.play()
        player = track
        scope.launch {
            for (pcm in playQueue) runCatching { player?.write(pcm, 0, pcm.size) }
        }
    }

    /** Barge-in: drop everything Saathi was about to say. */
    private fun flushPlayback() {
        while (playQueue.tryReceive().isSuccess) Unit
        player?.runCatching { pause(); flush(); play() }
        if (_state.value == LiveState.SPEAKING) _state.value = LiveState.LISTENING
    }

    /** SPEAKING while audio keeps arriving; back to LISTENING shortly after the last chunk. */
    private fun startSpeakingWatch() {
        scope.launch {
            while (isActive) {
                delay(200)
                if (_state.value == LiveState.SPEAKING && System.currentTimeMillis() - lastAudioAt > SPEAKING_TAIL_MS) {
                    _state.value = LiveState.LISTENING
                }
            }
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "live voice ended: $reason")
        if (_state.value == LiveState.ENDED || _state.value == LiveState.ERROR) return
        _state.value = LiveState.ERROR
        release()
        onEnded(connected)
    }

    private fun finish() {
        if (_state.value == LiveState.ENDED || _state.value == LiveState.ERROR) return
        _state.value = LiveState.ENDED
        release()
        onEnded(connected)
    }

    private fun release() {
        paused = true
        micJob?.cancel()
        playQueue.close()
        player?.runCatching { stop(); release() }
        player = null
        client.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SaathiLive"
        private const val SPEAKING_TAIL_MS = 700L
    }
}
