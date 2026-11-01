package com.saathi.app.features.scam

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import com.saathi.app.core.AppContainer
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.data.EventEntity
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.launch

/**
 * Asks before a call-forwarding code is dialled. Scammers ("delivery help, call this number") hand out a number
 * with a code in front, like *401*98765…; dialling it silently sends every call, and the bank's OTP calls, to
 * them. When such a code is typed in the phone app, a question covers the dialer (so Call can't be pressed by
 * habit) and says plainly what it does. "No" clears the number; "Yes" lets it through for that code.
 */
class DialGuard(private val container: AppContainer) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    /** The forwarding code ("*401*") being asked about, or already allowed with "Yes" (reset by "No"). */
    @Volatile private var asking: String? = null
    @Volatile private var allowed: String? = null

    /** The phone app, whichever the user set as default (Google, Samsung, Xiaomi...). */
    fun isDialer(pkg: String?): Boolean = pkg != null && (pkg == dialerPkg || pkg in KNOWN_DIALERS)

    private val dialerPkg: String? by lazy {
        runCatching { container.context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
    }

    /**
     * [text] is what the dialer's number field now shows. Main thread (accessibility events). Asks once the typing
     * pauses, so the question doesn't jump up between digits, and once per code (not again for every extra digit).
     */
    fun onDialerText(text: String) {
        pending?.let { main.removeCallbacks(it) }
        val (code, target) = UssdDetector.forwardingTarget(text) ?: return
        if (code == asking || code == allowed) return
        pending = Runnable { ask(code, target) }.also { main.postDelayed(it, SETTLE_MS) }
    }

    private fun ask(code: String, target: String) {
        if (code == asking || code == allowed) return
        val controller = OverlayService.controller ?: return
        asking = code
        val style = container.settings.current.replyStyle
        val question = tr(style, "Do you want to turn on call forwarding?", "क्या आप कॉल फ़ॉरवर्डिंग चालू करना चाहते हैं?",
            "Kya aap call forwarding on karna chahte hain?")
        val body = tr(style,
            "Then your calls will start going to another number ($target), even the bank's OTP calls. Delivery and customer care never ask you to dial this.",
            "इससे आपकी कॉल दूसरे नंबर ($target) पर जाने लगेंगी, बैंक की ओटीपी कॉल भी। डिलीवरी या कस्टमर केयर वाले कभी यह डायल नहीं करवाते।",
            "Isse aapki calls doosre number ($target) par jaane lagengi, bank ki OTP calls bhi. Delivery ya customer care wale kabhi yeh dial nahi karwaate.")
        val shown = controller.showDialCheck(question, body,
            no = tr(style, "No, don't do it", "नहीं, मत कीजिए", "Nahi, mat kijiye"),
            yes = tr(style, "Yes, I want it on", "हाँ, मुझे चालू करना है", "Haan, mujhe on karna hai"),
            onNo = { asking = null; container.speaker.stop(); clearDialer(code) },
            onYes = { asking = null; allowed = code; container.speaker.stop() },
            onNotShown = { asking = null })
        if (!shown) { asking = null; return }
        container.scope.launch {
            container.db.events().insert(EventEntity(type = "call", level = ScamLevel.AMBER.wire,
                reasons = "dialed_forwarding", detail = "target=$target", ts = System.currentTimeMillis()))
            container.speaker.say("$question $body", style)
        }
    }

    /** Empties the dialer's number field; if the field can't be found, leaves the phone app. */
    private fun clearDialer(code: String) {
        val svc = AgentService.instance ?: return
        // The field holding the code, not the dialer's search box.
        val field = svc.rootInActiveWindow?.let { root ->
            editables(root).firstOrNull { n -> n.text?.toString().orEmpty().filter { it.isDigit() || it in "*#+" }.startsWith(code) }
        }
        val cleared = field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }) == true
        if (!cleared) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun editables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (node.isEditable) out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { editables(it, out) }
        return out
    }

    /** Reads the number field after the phone app opens with a number already in it (a tapped tel: link). */
    fun scanWindow(root: AccessibilityNodeInfo?) {
        root ?: return
        editables(root).firstNotNullOfOrNull { n -> n.text?.toString()?.takeIf { UssdDetector.forwardingTarget(it) != null } }
            ?.let { onDialerText(it) }
    }

    private companion object {
        const val SETTLE_MS = 900L
        val KNOWN_DIALERS = setOf(
            "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer",
            "com.android.contacts", "com.oneplus.dialer", "com.android.incallui",
        )
    }
}
