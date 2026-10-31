package com.saathi.app.features.camera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.saathi.app.core.net.ProductCard
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Events coming back from the camera Live relay (docs/CONTRACTS.md). */
sealed interface LiveEvent {
    data class Card(val card: ProductCard) : LiveEvent
    data class Text(val text: String) : LiveEvent
    data class Audio(val pcm: ByteArray, val sampleRate: Int) : LiveEvent
    data class Error(val message: String) : LiveEvent
    data object Closed : LiveEvent
}

/**
 * WebSocket to backend `/live/camera`, which relays to Gemini Live. Frames go up as base64 JPEG, mic audio as
 * 16 kHz PCM16; product cards, captions and 24 kHz PCM audio come back. Reconnects once on a drop.
 */
class LiveSocket(private val url: String, private val replyStyle: String, private val onEvent: (LiveEvent) -> Unit) {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var reconnected = false
    @Volatile private var closedByUs = false

    fun connect() {
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(buildJsonObject { put("type", JsonPrimitive("config")); put("reply_style", JsonPrimitive(replyStyle)) })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                com.saathi.app.core.net.UsageMeter.record("live_camera", 0, text.length.toLong(), request = false)
                val obj = runCatching { SaathiJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                parse(obj)?.let(onEvent)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs) return
                if (!reconnected) {
                    reconnected = true
                    connect()
                } else {
                    onEvent(LiveEvent.Error(t.message ?: "connection lost"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEvent(LiveEvent.Closed)
        })
    }

    fun sendFrame(jpeg: ByteArray) = send(buildJsonObject {
        put("type", JsonPrimitive("frame"))
        put("jpeg_b64", JsonPrimitive(Base64.encodeToString(jpeg, Base64.NO_WRAP)))
    })

    fun sendAudio(pcm: ByteArray) = send(buildJsonObject {
        put("type", JsonPrimitive("audio"))
        put("pcm_b64", JsonPrimitive(Base64.encodeToString(pcm, Base64.NO_WRAP)))
    })

    fun close() {
        closedByUs = true
        send(buildJsonObject { put("type", JsonPrimitive("end")) })
        ws?.close(1000, "done")
        client.dispatcher.executorService.shutdown()
    }

    private fun send(obj: JsonObject) {
        val msg = obj.toString()
        com.saathi.app.core.net.UsageMeter.record("live_camera", msg.length.toLong(), 0, request = false)
        ws?.send(msg)
    }

    companion object {
        fun parse(obj: JsonObject): LiveEvent? = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "product_card" -> obj["card"]?.let { LiveEvent.Card(SaathiJson.decodeFromJsonElement(ProductCard.serializer(), it)) }
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { LiveEvent.Text(it) }
            "audio" -> obj["pcm_b64"]?.jsonPrimitive?.contentOrNull?.let {
                LiveEvent.Audio(Base64.decode(it, Base64.DEFAULT), obj["sample_rate"]?.jsonPrimitive?.int ?: 24_000)
            }
            "error" -> LiveEvent.Error(obj["message"]?.jsonPrimitive?.contentOrNull ?: "error")
            else -> null
        }
    }
}

/** Streams 16-bit mono PCM from the Live model to the speaker. */
class PcmPlayer(sampleRate: Int = 24_000) {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(com.saathi.app.core.voice.Speaker.SPEECH_USAGE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
        .build()
        .also { it.play() }

    fun write(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size)
    }

    fun release() {
        runCatching { track.stop() }
        track.release()
    }
}
