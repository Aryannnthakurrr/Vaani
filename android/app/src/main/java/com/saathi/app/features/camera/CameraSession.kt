package com.saathi.app.features.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ProductCard
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * One shopping-camera session (SPEC §7.3): ~1 fps frames + mic audio up to the Live relay, product card and
 * speech back. With the fake backend (no relay URL) a local simulation shows a fixture card after two frames,
 * like the backend mock, so the screen is demoable offline. Ends after 3 minutes without a new card or caption.
 */
class CameraSession(private val c: AppContainer) {
    private val _card = MutableStateFlow<ProductCard?>(null)
    val card: StateFlow<ProductCard?> = _card
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended

    val muted = MutableStateFlow(false)

    private val style: ReplyStyle get() = c.settings.current.replyStyle
    private var socket: LiveSocket? = null
    private var player: PcmPlayer? = null
    private var micJob: Job? = null
    private var watchdog: Job? = null
    private var lastFrameAt = 0L
    private var framesSent = 0
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun start() {
        val url = c.backend.liveCameraUrl()
        _caption.value = tr(style, "Hold the pack in front of the camera.", "पैकेट कैमरे के सामने रखिए।", "Packet camera ke saamne rakhiye.")
        if (url != null) {
            player = PcmPlayer()
            socket = LiveSocket(url, style.wire, ::onEvent).also { it.connect() }
            startMic()
        }
        watchdog = c.scope.launch {
            while (isActive) {
                delay(10_000)
                if (System.currentTimeMillis() - lastActivity > INACTIVITY_MS) { stop(); _ended.value = true }
            }
        }
        c.scope.launch { c.speaker.say(_caption.value, style) }
    }

    /** Called by the analyzer for every camera frame; throttled to ~1 fps and downscaled to 768 px. */
    fun onFrame(bitmap: () -> Bitmap?) {
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) return
        lastFrameAt = now
        val bmp = bitmap() ?: return
        framesSent++
        val s = socket
        if (s != null) {
            s.sendFrame(jpeg(bmp))
        } else if (framesSent == 2) {
            simulate()
        }
    }

    private fun jpeg(src: Bitmap): ByteArray {
        val scale = minOf(1f, 768f / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
    }

    private fun onEvent(e: LiveEvent) {
        lastActivity = System.currentTimeMillis()
        when (e) {
            is LiveEvent.Card -> _card.value = merge(_card.value, e.card)
            is LiveEvent.Text -> {
                _caption.value = e.text
                // The relay sends text-only replies in mock mode; speak them on the device.
                if (player == null || !hasAudio) c.scope.launch { c.speaker.say(e.text, style) }
            }
            is LiveEvent.Audio -> { hasAudio = true; player?.write(e.pcm) }
            is LiveEvent.Error -> _caption.value = tr(style, "Connection lost. Please try again.", "कनेक्शन टूट गया। फिर से कोशिश कीजिए।",
                "Connection toot gaya. Phir se try kijiye.")
            LiveEvent.Closed -> Unit
        }
    }

    @Volatile private var hasAudio = false

    /** Offline demo: what the backend mock returns, computed locally. */
    private fun simulate() {
        val expiry = LocalDate.now().plusDays(5)
        val card = ProductCard("Parle-G Gold Biscuits", "₹140", "1 kg", expiry.toString(), "soon", 0.9)
        onEvent(LiveEvent.Card(card))
        onEvent(LiveEvent.Text(tr(style,
            "This is Parle-G Gold, 1 kilo. The printed MRP is ₹140. It expires on ${expiry.dayOfMonth} ${expiry.month.name.lowercase().replaceFirstChar { it.uppercase() }}, in 5 days. Please use it soon.",
            "यह पारले-जी गोल्ड है, 1 किलो। छपा हुआ एमआरपी ₹140 है। यह 5 दिन में एक्सपायर हो जाएगा। जल्दी इस्तेमाल कीजिए।",
            "Yeh Parle-G Gold hai, 1 kilo. Printed MRP ₹140 hai. Yeh 5 din mein expire ho jayega. Jaldi use kijiye.")))
    }

    private fun merge(old: ProductCard?, new: ProductCard) = if (old == null) new else ProductCard(
        name = new.name ?: old.name, mrp = new.mrp ?: old.mrp, quantity = new.quantity ?: old.quantity,
        expiryIso = new.expiryIso ?: old.expiryIso,
        expiryStatus = if (new.expiryStatus != "unknown") new.expiryStatus else old.expiryStatus,
        confidence = new.confidence ?: old.confidence,
    )

    fun readAgain() {
        val card = _card.value ?: return
        val status = when (card.expiryStatus) {
            "expired" -> tr(style, "It has EXPIRED. Do not buy it.", "यह एक्सपायर हो चुका है। इसे मत खरीदिए।", "Yeh expire ho chuka hai. Ise mat kharidiye.")
            "soon" -> tr(style, "It expires soon.", "यह जल्दी एक्सपायर होगा।", "Yeh jaldi expire hoga.")
            "ok" -> tr(style, "The expiry date is fine.", "एक्सपायरी ठीक है।", "Expiry theek hai.")
            else -> tr(style, "I could not read the expiry. Please turn the pack over.", "मैं एक्सपायरी नहीं पढ़ पाया। पैकेट पलटिए।",
                "Main expiry nahi padh paya. Packet palatiye.")
        }
        val line = listOfNotNull(card.name, card.quantity, card.mrpText?.let { tr(style, "Printed MRP $it.", "छपा एमआरपी $it।", "Printed MRP $it.") }, status)
            .joinToString(". ")
        c.scope.launch { c.speaker.say(line, style) }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (c.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micJob = c.scope.launch(Dispatchers.IO) {
            val rate = 16_000
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, rate)
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@launch
            val buf = ByteArray(rate / 10 * 2) // 100 ms
            rec.startRecording()
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0 && !muted.value) socket?.sendAudio(buf.copyOf(n))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    fun stop() {
        micJob?.cancel()
        watchdog?.cancel()
        socket?.close()
        socket = null
        player?.release()
        player = null
        c.speaker.stop()
    }

    companion object {
        const val FRAME_INTERVAL_MS = 1_000L
        const val INACTIVITY_MS = 3 * 60_000L
    }
}
