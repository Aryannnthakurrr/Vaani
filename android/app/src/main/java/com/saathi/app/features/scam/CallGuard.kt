package com.saathi.app.features.scam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.saathi.app.core.AppContainer
import com.saathi.app.core.data.EventEntity
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.overlay.ScamAlert
import com.saathi.app.core.voice.tr
import com.saathi.app.util.Packages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Watches phone calls for scam signals (SPEC §7.6 F3): an OTP arriving, a money app or a
 * remote-access app opening, or screen sharing starting while OFFHOOK. Scores them with [RiskScorer]
 * and escalates once per level per call, speaking pre-rendered phrases through the earpiece.
 */
class CallGuard(private val container: AppContainer) {
    private val context: Context get() = container.context
    private val lock = Mutex()

    @Volatile private var state = TelephonyManager.EXTRA_STATE_IDLE
    @Volatile private var number: String? = null
    @Volatile private var savedContact = false
    @Volatile private var offhookAt = 0L
    @Volatile private var otp = false
    @Volatile private var moneyApp = false
    @Volatile private var remoteApp = false
    @Volatile private var alerted = ScamLevel.NONE
    /** When the last OTP arrived: scammers often trigger it just before they call, then ask for it. */
    @Volatile private var lastOtpAt = 0L
    @Volatile private var voipOtpAlertAt = 0L

    val inCall: Boolean get() = state == TelephonyManager.EXTRA_STATE_OFFHOOK

    /** A WhatsApp / Meet style internet call: no phone call, but the audio is in call mode. */
    private val inInternetCall: Boolean
        get() = context.getSystemService(android.media.AudioManager::class.java)?.mode ==
            android.media.AudioManager.MODE_IN_COMMUNICATION

    fun onCallStateChanged(newState: String, incomingNumber: String?) {
        if (!incomingNumber.isNullOrBlank()) {
            number = incomingNumber
            savedContact = container.contacts.isSavedContact(incomingNumber)
        }
        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (state == TelephonyManager.EXTRA_STATE_IDLE) resetSignals()
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (state != TelephonyManager.EXTRA_STATE_OFFHOOK) {
                offhookAt = System.currentTimeMillis()
                // An OTP that came in just before an incoming call counts as one during it. (Not for calls the user
                // places: their number isn't known here, so a call to family would look like a stranger.)
                if (state == TelephonyManager.EXTRA_STATE_RINGING && offhookAt - lastOtpAt < OTP_BEFORE_CALL_MS) {
                    otp = true
                    evaluate("call_red_otp")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                resetSignals()
                number = null
                savedContact = false
            }
        }
        state = newState
    }

    private fun resetSignals() {
        otp = false
        moneyApp = false
        remoteApp = false
        alerted = ScamLevel.NONE
        offhookAt = 0L
    }

    /** Called for every OTP message, in a call or not. */
    fun onOtpArrived() {
        lastOtpAt = System.currentTimeMillis()
        if (inCall) {
            otp = true
            evaluate("call_red_otp")
        } else if (inInternetCall) {
            warnOtpOnInternetCall()
        }
    }

    /**
     * WhatsApp calls don't say who is calling, so this can't be scored like a phone call. An OTP during one is
     * still the classic scam, so the warning is the same plain advice: tell no one.
     */
    private fun warnOtpOnInternetCall() {
        val now = System.currentTimeMillis()
        if (now - voipOtpAlertAt < 60_000) return
        voipOtpAlertAt = now
        container.scope.launch {
            val style = container.settings.current.replyStyle
            container.db.events().insert(EventEntity(type = "call", level = ScamLevel.RED.wire,
                reasons = "otp_during_call", detail = "internet_call", ts = now))
            vibrate(strong = true)
            val alert = ScamAlert(com.saathi.app.core.overlay.AlertLevel.RED,
                tr(style, "Don't share this OTP", "यह ओटीपी किसी को मत बताइए", "Yeh OTP kisi ko mat bataiye"),
                ReasonText.of("otp_during_call", style) + "\n" + otpAdvice(style), "call_red_otp")
            withContext(Dispatchers.Main) { container.alerts.show(alert) }
            container.speaker.sayPhrase("call_red_otp", style)
        }
    }

    fun onForegroundPackage(pkg: String) {
        if (!inCall) return
        when (pkg) {
            in Packages.REMOTE_ACCESS_APPS -> if (!remoteApp) { remoteApp = true; evaluate("call_red_remote_app") }
            in Packages.MONEY_APPS -> if (!moneyApp) { moneyApp = true; evaluate("call_red_money_app") }
        }
    }

    fun onScreenShareSuspected() {
        if (!inCall || remoteApp) return
        remoteApp = true
        evaluate("call_red_remote_app")
    }

    private fun evaluate(redPhrase: String) {
        container.scope.launch {
            lock.withLock {
                val since = System.currentTimeMillis() - 24 * 3600_000L
                val recent = container.db.events().since(since).filter { it.type == "message" }
                    .fold(ScamLevel.NONE) { acc, e -> acc.max(ScamLevel.from(e.level)) }
                val signals = CallSignals(
                    savedContact = savedContact,
                    durationMs = if (offhookAt > 0) System.currentTimeMillis() - offhookAt else 0,
                    otpDuringCall = otp, moneyAppDuringCall = moneyApp, remoteAppDuringCall = remoteApp,
                    recentMessageLevel = recent,
                )
                val risk = RiskScorer.score(signals)
                container.db.events().insert(EventEntity(
                    type = "call", level = risk.level.wire.takeIf { risk.level != ScamLevel.NONE } ?: "info",
                    reasons = risk.reasons.joinToString(","), detail = "score=${risk.score}",
                    ts = System.currentTimeMillis(),
                ))
                if (risk.level <= alerted || risk.level < ScamLevel.AMBER) return@withLock
                alerted = risk.level
                react(risk, if (risk.level == ScamLevel.RED) redPhrase else "call_amber")
            }
        }
    }

    private suspend fun react(risk: RiskScore, phraseKey: String) {
        val settings = container.settings.current
        val style = settings.replyStyle
        vibrate(risk.level == ScamLevel.RED)
        val otpCase = "otp_during_call" in risk.reasons
        val title = when {
            // The one thing to do, as the title: an OTP on a call is never to be read out.
            otpCase && risk.level == ScamLevel.RED ->
                tr(style, "Danger: don't share the OTP on this call", "खतरा: इस कॉल पर ओटीपी मत बताइए", "Khatra: is call par OTP mat bataiye")
            risk.level == ScamLevel.RED ->
                tr(style, "Danger: this call may be a scam", "खतरा: यह कॉल धोखा हो सकती है", "Khatra: yeh call dhokha ho sakti hai")
            else -> tr(style, "Be careful on this call", "इस कॉल पर सावधान रहें", "Is call par savdhaan rahein")
        }
        val advice = listOfNotNull(
            otpAdvice(style).takeIf { otpCase },
            tr(style, "Don't send money because someone on a call asks.", "कॉल पर किसी के कहने से पैसे मत भेजिए।",
                "Call par kisi ke kehne se paise mat bhejiye.").takeIf { "money_app_during_call" in risk.reasons },
            tr(style, "Stop the screen sharing now.", "स्क्रीन शेयर अभी बंद कीजिए।", "Screen share abhi band kijiye.")
                .takeIf { "remote_app_during_call" in risk.reasons },
        )
        val body = (listOf(ReasonText.join(risk.reasons, style)) + advice).joinToString("\n")
        val from = number?.takeIf { it.isNotBlank() }?.let { tr(style, "Call from $it", "$it से कॉल", "$it se call") }
        val alert = ScamAlert(risk.level.toAlertLevel(), title, body, phraseKey, inCall = true, from = from)
        withContext(Dispatchers.Main) { container.alerts.show(alert) }
        container.speaker.speakInCall(phraseKey, style)
        if (risk.level == ScamLevel.RED && settings.notifyFamilyOnScam) {
            val who = settings.userName.ifBlank { "Your family member" }
            val summary = "$who may be on a scam call right now (${ReasonText.join(risk.reasons, ReplyStyle.EN)}). Please call them."
            runCatching { container.backend.notifyFamily(summary) }
        }
    }

    private fun otpAdvice(style: ReplyStyle) = tr(style,
        "No bank, police, delivery or customer care ever asks for an OTP. Tell it to no one.",
        "बैंक, पुलिस, डिलीवरी या कस्टमर केयर वाले कभी ओटीपी नहीं माँगते। किसी को मत बताइए।",
        "Bank, police, delivery ya customer care wale kabhi OTP nahi maangte. Kisi ko mat bataiye.")

    private fun vibrate(strong: Boolean) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        val pattern = if (strong) longArrayOf(0, 800, 200, 800, 200, 800, 200, 800) else longArrayOf(0, 400, 300, 400)
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /**
     * Tries to hang up. `TelecomManager.endCall()` is deprecated but still works for apps holding
     * ANSWER_PHONE_CALLS; when it is refused we tell the user to press the red button.
     */
    fun endCall(): Boolean {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED && runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.endCall() == true
        }.getOrDefault(false)
        if (!ok) {
            container.scope.launch { container.speaker.speakInCall("call_press_red", container.settings.current.replyStyle) }
        }
        return ok
    }

    private companion object {
        const val OTP_BEFORE_CALL_MS = 2 * 60 * 1000L
    }
}
