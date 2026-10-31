package com.saathi.app.core.agent

import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.util.Packages

/** Why the agent must pause before reading or acting on a screen. */
enum class ScreenStop { SECRET_ENTRY, LOGIN, FOREIGN_APP, SETTINGS_RESTRICTED }

sealed interface Verdict {
    data object Allow : Verdict
    /** The action would press an irreversible button: only allowed inside a spoken-yes `confirm`. */
    data class NeedsConfirm(val target: String) : Verdict
    data class Block(val why: String) : Verdict
}

/**
 * Hard-coded safety rules (SPEC §6.4). Everything here is pure so it is unit-tested;
 * prompts restate these rules but code is what enforces them.
 */
object SafetyPolicy {

    /** Buttons that commit money, bookings or messages. EN + HI. */
    val CONFIRM_WORDS = Regex(
        "(?i)\\b(book|booking|confirm|pay|place order|buy now|proceed to pay|send|request|schedule|add to cart)\\b" +
            "|बुक|पुष्टि|भुगतान|ऑर्डर|भेजें|कार्ट में",
    )

    private val SECRET_LABEL = Regex("(?i)\\bupi pin\\b|enter (your )?pin|\\bm-?pin\\b|\\bcvv\\b|\\bcvc\\b|यूपीआई पिन|पिन डालें")
    private val OTP_LABEL = Regex("(?i)\\botp\\b|one[ -]time password|verification code|ओटीपी")
    private val PASSWORD_LABEL = Regex("(?i)password|passcode|पासवर्ड")
    private val DIAL_CODE = Regex("[*#][0-9*#]{2,}")

    /** System surfaces the agent may pass through in ACT mode (share sheet, permission prompts). */
    val SYSTEM_PACKAGES = setOf(
        "android", "com.android.systemui", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.google.android.inputmethod.latin",
    )

    /** Inspect a screen before asking the model anything. */
    fun preCheck(s: ScreenSnapshot, mode: AgentMode, allowedPackages: Set<String> = Packages.ACT_ALLOWLIST): ScreenStop? {
        if (isSecretScreen(s)) return ScreenStop.SECRET_ENTRY
        if (mode == AgentMode.ACT && isLoginScreen(s)) return ScreenStop.LOGIN
        if (mode == AgentMode.ACT && s.pkg !in allowedPackages && s.pkg !in SYSTEM_PACKAGES) return ScreenStop.FOREIGN_APP
        if (mode == AgentMode.ACT && s.pkg == Packages.SETTINGS && isRestrictedSettingsPage(s)) return ScreenStop.SETTINGS_RESTRICTED
        return null
    }

    /**
     * Settings is allowed only for look-and-sound changes (text size, display, dark mode, volume, brightness). A page
     * whose title names anything else (security, accounts, apps, developer options, location, reset…) stops the agent,
     * and on the Settings home list it may not tap those rows.
     */
    private val RESTRICTED_SETTINGS = Regex(
        "(?i)\\b(security|privacy|accounts?|passwords?|passkeys?|apps|applications|developer|accessibility|location|reset|" +
            "backup|biometrics?|fingerprints?|face unlock|lock screen|screen lock|device admin|install unknown|permissions?|" +
            "users|google|payments?|sim|connections|wi-?fi|bluetooth|nfc|mobile networks?|about phone|software update)\\b" +
            "|सुरक्षा|गोपनीयता|खाता|खाते|पासवर्ड|ऐप्स|डेवलपर|सुलभता|लोकेशन|स्थान|रीसेट|बैकअप|अनुमति",
    )

    fun isRestrictedSettingsPage(s: ScreenSnapshot): Boolean {
        val heading = s.title?.takeIf { it.isNotBlank() && !it.equals("Settings", ignoreCase = true) && it != "सेटिंग्स" }
            ?: return false
        return RESTRICTED_SETTINGS.containsMatchIn(heading)
    }

    fun isRestrictedSettingsRow(node: ScreenNode?): Boolean = node != null && RESTRICTED_SETTINGS.containsMatchIn(node.allText())

    /** PIN / CVV / OTP entry screens and FLAG_SECURE windows: the user types, never the agent. */
    fun isSecretScreen(s: ScreenSnapshot): Boolean {
        if (s.secure) return true
        val hasInput = s.nodes.any { it.editable }
        if (s.nodes.any { it.editable && (SECRET_LABEL.containsMatchIn(it.allText()) || OTP_LABEL.containsMatchIn(it.allText())) }) return true
        return hasInput && s.nodes.any { SECRET_LABEL.containsMatchIn(it.allText()) }
    }

    fun isLoginScreen(s: ScreenSnapshot): Boolean =
        s.nodes.any { it.editable && (it.password || PASSWORD_LABEL.containsMatchIn(it.allText())) }

    fun isConfirmTarget(node: ScreenNode?): Boolean = node != null && CONFIRM_WORDS.containsMatchIn(node.allText())

    /**
     * Validates one action against the current screen.
     * @param confirmed true only when this action is the `then` of a `confirm` the user said yes to.
     * @param trustedDeeplinks URIs built by Saathi's own task code; nothing else may be opened.
     */
    fun validate(
        action: AgentAction,
        s: ScreenSnapshot,
        mode: AgentMode,
        confirmed: Boolean = false,
        trustedDeeplinks: Set<String> = emptySet(),
        allowedPackages: Set<String> = Packages.ACT_ALLOWLIST,
    ): Verdict {
        if (action.action !in AgentAction.ALL) return Verdict.Block("unknown action ${action.action}")
        if (mode == AgentMode.GUIDE && action.action !in GUIDE_ACTIONS) {
            return Verdict.Block("guide mode never ${action.action}s")
        }
        return when (action.action) {
            AgentAction.TAP -> {
                val node = s.node(action.id) ?: return Verdict.Block("no element ${action.id}")
                if (s.pkg == Packages.SETTINGS && isRestrictedSettingsRow(node)) return Verdict.Block("Settings: only text size, sound and display")
                if (isConfirmTarget(node) && !confirmed) Verdict.NeedsConfirm(node.label) else Verdict.Allow
            }
            AgentAction.TAP_XY -> {
                val x = action.x ?: return Verdict.Block("missing x")
                val y = action.y ?: return Verdict.Block("missing y")
                if (s.screenshotB64 == null && !s.needsScreenshot) return Verdict.Block("coordinates only with a screenshot")
                val node = s.nodeAt(x, y)
                if (s.pkg == Packages.SETTINGS && isRestrictedSettingsRow(node)) return Verdict.Block("Settings: only text size, sound and display")
                if (isConfirmTarget(node) && !confirmed) Verdict.NeedsConfirm(node!!.label) else Verdict.Allow
            }
            AgentAction.TYPE -> {
                val node = s.node(action.id) ?: return Verdict.Block("no element ${action.id}")
                val text = action.text.orEmpty()
                when {
                    !node.editable -> Verdict.Block("element ${action.id} is not a text box")
                    node.password || PASSWORD_LABEL.containsMatchIn(node.allText()) -> Verdict.Block("never types passwords")
                    SECRET_LABEL.containsMatchIn(node.allText()) || OTP_LABEL.containsMatchIn(node.allText()) ->
                        Verdict.Block("never types PINs or OTPs")
                    DIAL_CODE.containsMatchIn(text) -> Verdict.Block("never types dial codes")
                    else -> Verdict.Allow
                }
            }
            AgentAction.OPEN_APP -> {
                val pkg = action.pkg ?: return Verdict.Block("missing package")
                if (pkg in allowedPackages) Verdict.Allow else Verdict.Block("$pkg is not on the allowlist")
            }
            AgentAction.OPEN_DEEPLINK -> {
                val uri = action.uri ?: return Verdict.Block("missing uri")
                when {
                    uri.startsWith("tel:", true) || uri.startsWith("sms:", true) -> Verdict.Block("never dials or texts")
                    uri in trustedDeeplinks -> Verdict.Allow
                    else -> Verdict.Block("deep link not built by Saathi")
                }
            }
            AgentAction.CONFIRM -> {
                val inner = action.then ?: return Verdict.Block("confirm without an action")
                if (action.summary.isNullOrBlank()) return Verdict.Block("confirm needs a summary")
                if (inner.action == AgentAction.CONFIRM) return Verdict.Block("nested confirm")
                // The inner action is validated again (with confirmed=true) after the user says yes.
                when (val v = validate(inner, s, mode, confirmed = true, trustedDeeplinks, allowedPackages)) {
                    is Verdict.Block -> v
                    else -> Verdict.Allow
                }
            }
            AgentAction.HIGHLIGHT -> if (mode == AgentMode.GUIDE) Verdict.Allow else Verdict.Block("highlight is guide-only")
            else -> Verdict.Allow
        }
    }

    val GUIDE_ACTIONS = setOf(AgentAction.HIGHLIGHT, AgentAction.ASK_USER, AgentAction.DONE, AgentAction.FAIL, AgentAction.WAIT)
}
