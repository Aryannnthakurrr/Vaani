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

    /** Whether Saathi can hear the user: the Mute button only makes sense while the mic is streaming. */
    enum class Mic { STARTING, LISTENING, OFFLINE, UNAVAILABLE, LOST }
    private val _mic = MutableStateFlow(Mic.STARTING)
    val mic: StateFlow<Mic> = _mic

    private val style: ReplyStyle get() = c.settings.current.replyStyle
    private var socket: LiveSocket? = null
    private var player: PcmPlayer? = null
    private var micJob: Job? = null
    private var watchdog: Job? = null
    /** Welcome line, then connect: cancelled by [stop], so the mic and socket never start after the screen is gone. */
    private var startJob: Job? = null
    /** Phone speech (product description, read again): cancelling it stops the playback, without touching other speech. */
    private var sayJob: Job? = null
    private var url: String? = null
    @Volatile private var stopped = false
    private var lastFrameAt = 0L
    private var framesSent = 0
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun start() {
        stopped = false
        url = c.backend.liveCameraUrl()
        online = url != null
        _mic.value = if (online) Mic.STARTING else Mic.OFFLINE
        val instruction = tr(style, "Hold the pack in front of the camera.", "पैकेट कैमरे के सामने रखिए।", "Packet camera ke saamne rakhiye.")
        _caption.value = instruction
        // Opened by voice, the assistant has just said this (or the live conversation will): don't say it twice.
        val welcome = System.currentTimeMillis() - instructedAt > INSTRUCTED_FRESH_MS && c.assistant.live.value == null
        instructedAt = 0L
        // Connect first and say the welcome line meanwhile: the camera pictures then stream during the line, so the
        // model has seen a few sharp seconds of the pack by the time the user asks. Connecting only after the line
        // left it one or two blurry, still-focusing frames, and the first question got "I can't identify it". The
        // voices can't overlap: the model only answers the user, and the mic is off while the welcome line plays.
        // On the main thread, like stop(), so a Close during the welcome line can't race the socket opening.
        startJob = c.scope.launch(Dispatchers.Main) {
            connect()
            if (welcome) sayLocal(instruction)
        }
        watchdog = c.scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(10_000)
                if (System.currentTimeMillis() - lastActivity > INACTIVITY_MS) { stop(); _ended.value = true }
            }
        }
    }

    /** Opens the Live socket and the mic (online only). Main thread. */
    private fun connect() {
        val u = url ?: return
        if (stopped) return
        player = PcmPlayer()
        socket = LiveSocket(u, style.wire, ::onEvent, shopperContext()).also { it.connect() }
        startMic()
    }

    /** "Connection lost" → Retry: a fresh socket and mic. */
    fun retry() {
        if (stopped || _mic.value != Mic.LOST) return
        lastActivity = System.currentTimeMillis()
        _mic.value = Mic.STARTING
        _caption.value = tr(style, "Hold the pack in front of the camera.", "पैकेट कैमरे के सामने रखिए।", "Packet camera ke saamne rakhiye.")
        connect()
    }

    /** The relay gave up: stop streaming the mic into a dead socket and offer Retry. Main thread. */
    private fun dropConnection() {
        if (stopped) return
        micJob?.cancel()
        micJob = null
        socket?.close()
        socket = null
        player?.release()
        player = null
        turnText.clear()
        audioThisTurn = false
        _mic.value = Mic.LOST
        _caption.value = tr(style, "Connection lost. Tap Retry to try again.", "कनेक्शन टूट गया। फिर से कोशिश करने के लिए 'फिर से' दबाइए।",
            "Connection toot gaya. Dobara try karne ke liye 'Retry' dabaiye.")
    }

    private fun speak(text: String) {
        if (stopped) return
        sayJob?.cancel()
        sayJob = c.scope.launch(Dispatchers.Main) { sayLocal(text) }
    }

    /** Speaks on the phone (Sarvam) with the microphone muted meanwhile, so the model doesn't hear it. */
    private suspend fun sayLocal(text: String) {
        localSpeaking = true
        try {
            c.speaker.say(text, style)
        } finally {
            localSpeaking = false
        }
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
        } else if (!online && framesSent == 2) {
            simulate()
        }
    }

    /**
     * MRP and expiry are tiny dot-printed text: at 768 px and quality 75 the model half-read them ("₹14", "10/04"),
     * said a wrong value and corrected itself a frame later. 1280 px at quality 88 keeps the digits legible.
     */
    private fun jpeg(src: Bitmap): ByteArray {
        val scale = minOf(1f, MAX_SIDE_PX / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    private fun onEvent(e: LiveEvent) {
        lastActivity = System.currentTimeMillis()
        when (e) {
            is LiveEvent.Card -> _card.value = merge(_card.value, e.card)
            // Text arrives in fragments of the reply being spoken: build one caption per turn.
            is LiveEvent.Text -> {
                turnText.append(e.text)
                _caption.value = turnText.toString().trim()
            }
            is LiveEvent.Audio -> { audioThisTurn = true; liveVoice = true; player?.write(e.pcm) }
            LiveEvent.TurnComplete -> {
                // Only text-only replies (mock mode, offline demo) are spoken on the phone, once, as a whole. Once the
                // model has spoken with its own voice, never: its transcript often trails in after its turn ends (and
                // a card update ends a turn with no audio), and the phone then read the whole description again in a
                // second voice.
                val text = turnText.toString().trim()
                if (!audioThisTurn && !liveVoice && text.isNotEmpty()) speak(text)
                turnText.clear()
                audioThisTurn = false
            }
            LiveEvent.Interrupted -> {
                player?.flush()
                turnText.clear()
                audioThisTurn = false
            }
            is LiveEvent.Error -> c.scope.launch(Dispatchers.Main) { dropConnection() }
            LiveEvent.Closed -> Unit
        }
    }

    private val turnText = StringBuilder()
    @Volatile private var audioThisTurn = false
    /** The model has spoken with its own (Gemini) voice in this session: the phone then never speaks its text. */
    @Volatile private var liveVoice = false
    @Volatile private var localSpeaking = false
    @Volatile private var online = false

    /** Offline demo: what the backend mock returns, computed locally. */
    private fun simulate() {
        val expiry = LocalDate.now().plusDays(5)
        val card = ProductCard("Parle-G Gold Biscuits", "₹140", "1 kg", expiry.toString(), "soon", 0.9)
        onEvent(LiveEvent.Card(card))
        onEvent(LiveEvent.Text(tr(style,
            "This is Parle-G Gold, 1 kilo. The printed MRP is ₹140. It expires on ${expiry.dayOfMonth} ${expiry.month.name.lowercase().replaceFirstChar { it.uppercase() }}, in 5 days. Please use it soon.",
            "यह पारले-जी गोल्ड है, 1 किलो। छपा हुआ एमआरपी ₹140 है। यह 5 दिन में एक्सपायर हो जाएगा। जल्दी इस्तेमाल कीजिए।",
            "Yeh Parle-G Gold hai, 1 kilo. Printed MRP ₹140 hai. Yeh 5 din mein expire ho jayega. Jaldi use kijiye.")))
        onEvent(LiveEvent.TurnComplete)
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
            else -> tr(style, "I could not read the expiry. Please turn the pack over.", "मैं एक्सपायरी नहीं पढ़ पाई। पैकेट पलटिए।",
                "Main expiry nahi padh payi. Packet palatiye.")
        }
        val line = listOfNotNull(card.name, card.quantity, card.mrpText?.let { tr(style, "Printed MRP $it.", "छपा एमआरपी $it।", "Printed MRP $it.") }, status, card.healthWarning)
            .joinToString(". ")
        player?.flush() // "read again" replaces whatever the model was saying
        speak(line)
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (c.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _mic.value = Mic.UNAVAILABLE
            return
        }
        micJob = c.scope.launch(Dispatchers.IO) {
            val rate = 16_000
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, rate)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                _mic.value = Mic.UNAVAILABLE
                return@launch
            }
            val buf = ByteArray(rate / 10 * 2) // 100 ms
            rec.startRecording()
            if (isActive) _mic.value = Mic.LISTENING
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    // Half-duplex: no mic audio while either voice is speaking, or the model hears itself and
                    // answers its own words (the overlapping replies users heard). Talk once it stops.
                    val talking = localSpeaking || player?.speaking == true
                    if (n > 0 && !muted.value && !talking) socket?.sendAudio(buf.copyOf(n))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    /**
     * Ends the session; safe to call twice. Only this session's own speech is cancelled (that stops its playback),
     * never the shared speaker, so a task started from "Order online" keeps its first line.
     */
    fun stop() {
        stopped = true
        startJob?.cancel()
        sayJob?.cancel()
        micJob?.cancel()
        watchdog?.cancel()
        socket?.close()
        socket = null
        player?.release()
        player = null
    }

    companion object {
        const val FRAME_INTERVAL_MS = 1_000L
        private const val MAX_SIDE_PX = 1280f
        private const val JPEG_QUALITY = 88
        const val INACTIVITY_MS = 3 * 60_000L
        private const val INSTRUCTED_FRESH_MS = 15_000L

        @Volatile private var instructedAt = 0L

        /**
         * Call right after saying "hold the pack in front of the camera" and before opening the camera screen: the
         * screen then skips its own welcome line instead of saying it a second time.
         */
        fun instructionSpoken() { instructedAt = System.currentTimeMillis() }
    }

    /** Profile and next appointment, so the camera can say "very salty, and your BP check is on Monday". */
    private fun shopperContext(): String? {
        val appt = c.settings.current.lastAppointment?.let {
            runCatching { com.saathi.app.util.SaathiJson.decodeFromString(com.saathi.app.core.net.Appointment.serializer(), it) }.getOrNull()
        }
        val reason = c.profiles.current()?.appointment?.reason?.takeIf { it.isNotBlank() }?.let { "Reason for the visit: $it." }
        return listOfNotNull(
            c.profiles.contextLine(),
            com.saathi.app.features.appointments.AppointmentState.contextLine(appt, java.time.ZonedDateTime.now()),
            reason,
        ).joinToString("\n").ifBlank { null }
    }
}
