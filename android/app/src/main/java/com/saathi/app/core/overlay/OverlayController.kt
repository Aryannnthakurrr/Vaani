package com.saathi.app.core.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.saathi.app.MainActivity
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantScreen
import com.saathi.app.core.AssistantScreen.Prompt
import com.saathi.app.core.VoiceStatus
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Draws the assistant's [AssistantScreen] as floating windows over other apps: mic bubble, caption card, stop chip,
 * highlight ring, question cards and the result card. Nothing else adds or removes these windows; they follow the
 * state, so they can't be left behind. While Saathi's own screen is in front none of them show (the in-app sheet
 * draws the same state there). The scam alert is the one window shown on request ([showAlert]).
 *
 * Windows are TYPE_ACCESSIBILITY_OVERLAY when the accessibility service is on (no extra permission and always on
 * top), otherwise TYPE_APPLICATION_OVERLAY. Everything runs on the main thread.
 */
class OverlayController(private val ctx: Context, private val container: AppContainer) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val added = HashMap<View, WindowManager>()
    private val screen: AssistantScreen get() = container.assistant.screen
    private val style get() = container.settings.current.replyStyle
    private fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)

    private var bubble: BubbleView? = null
    private var stopChip: View? = null
    private var caption: OverlayViews.Caption? = null
    private var shownCaption: AssistantScreen.Caption? = null
    private var highlight: HighlightView? = null
    private var promptView: View? = null
    private var shownPrompt: Prompt? = null
    private var resultView: View? = null
    private var shownResult: AssistantScreen.Result? = null
    private var alertView: View? = null

    init {
        scope.launch {
            merge(screen.caption, screen.prompt, screen.running, screen.highlight, screen.result, screen.foreground)
                .collect { render() }
        }
        scope.launch {
            container.assistant.status.collect { st ->
                bubble?.let { b ->
                    b.status = st
                    b.contentDescription = bubbleLabel(st)
                }
            }
        }
    }

    fun canDraw(): Boolean = AgentService.instance != null || Settings.canDrawOverlays(ctx)

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val away = !screen.foreground.value
        renderBubble(away)
        val newRing = renderHighlight(if (away) screen.highlight.value else null)
        val prompt = if (away) screen.prompt.value else null
        renderPrompt(prompt)
        // While a question card is open it carries the question itself: no second card repeating it above.
        renderCaption(if (away && prompt == null) screen.caption.value else null, restack = newRing)
        renderStop(away && screen.running.value, restack = newRing)
        renderResult(if (away) screen.result.value else null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderBubble(show: Boolean) {
        if (!show) { remove(bubble); bubble = null; return }
        if (bubble != null) return
        val v = BubbleView(ctx) { container.assistant.voiceLevel() }
        val size = OverlayViews.dp(ctx, BubbleView.WINDOW_DP)
        var lp: WindowManager.LayoutParams? = null
        val ok = add(v) { type ->
            params(size, size, Gravity.TOP or Gravity.START, type = type).apply {
                x = ctx.resources.displayMetrics.widthPixels - size
                y = ctx.resources.displayMetrics.heightPixels / 2
            }.also { lp = it }
        }
        if (!ok) return
        v.status = container.assistant.status.value
        v.contentDescription = bubbleLabel(v.status)
        bubble = v
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var longFired = false
        val longPress = Runnable {
            longFired = true
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        v.setOnTouchListener { view, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; longFired = false
                    main.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        added[view]?.runCatching { updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longFired) { view.performClick(); container.assistant.onMicTapped() }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
    }

    private fun bubbleLabel(st: VoiceStatus) = when (st) {
        VoiceStatus.IDLE -> s("Vaani is not listening. Tap to talk. Long press to open Vaani.",
            "वाणी अभी नहीं सुन रही। बात करने के लिए दबाइए। वाणी खोलने के लिए दबाए रखिए।",
            "Vaani abhi nahi sun rahi. Baat karne ke liye dabaiye. Vaani kholne ke liye dabaye rakhiye.")
        VoiceStatus.CONNECTING -> s("Connecting. Tap to end.", "जुड़ रही हूँ। बंद करने के लिए दबाइए।", "Connect ho rahi hoon. Band karne ke liye dabaiye.")
        VoiceStatus.LISTENING -> s("Vaani is listening. Tap to end.", "वाणी सुन रही है। बंद करने के लिए दबाइए।", "Vaani sun rahi hai. Band karne ke liye dabaiye.")
        VoiceStatus.SPEAKING -> s("Vaani is speaking. Tap to end.", "वाणी बोल रही है। बंद करने के लिए दबाइए।", "Vaani bol rahi hai. Band karne ke liye dabaiye.")
        VoiceStatus.WORKING -> s("Vaani is working. Tap to stop.", "वाणी काम कर रही है। रोकने के लिए दबाइए।", "Vaani kaam kar rahi hai. Rokne ke liye dabaiye.")
    }

    /** Returns true when the ring layer was just added, so the caption and Stop chip must be put back on top of it. */
    private fun renderHighlight(h: AssistantScreen.Highlight?): Boolean {
        if (h == null) { remove(highlight); highlight = null; return false }
        var fresh = false
        val view = highlight ?: HighlightView(ctx).also { hv ->
            if (!add(hv) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START, touchable = false, type = type) }) return false
            highlight = hv
            fresh = true
        }
        view.gesture = h.gesture
        view.target = h.bounds
        return fresh
    }

    private fun renderCaption(c: AssistantScreen.Caption?, restack: Boolean) {
        if (c == null || restack) { caption?.let { remove(it.root) }; caption = null; shownCaption = null }
        if (c == null) return
        val card = caption ?: OverlayViews.Caption(ctx).also { card ->
            if (!add(card.root) { type ->
                    params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, type = type)
                        .apply { y = OverlayViews.dp(ctx, 40); horizontalMargin = 0f }
                }) return
            caption = card
        }
        applyCompact(card, screen.running.value)
        if (c == shownCaption) return
        shownCaption = c
        card.label.text = c.text
        card.label.contentDescription = c.text
        card.status.text = c.status
        card.status.visibility = if (c.status.isEmpty()) View.GONE else View.VISIBLE
        if (c.button != null && c.onButton != null) {
            card.button.text = c.button
            card.button.setOnClickListener { c.onButton?.invoke() }
            card.button.visibility = View.VISIBLE
        } else {
            card.button.visibility = View.GONE
        }
    }

    /**
     * While Saathi works inside another app the caption shrinks to a slim bar at the top (smaller type, three lines
     * at most), so it no longer covers the app's buttons and time slots. Questions still show in full on their cards.
     */
    private fun applyCompact(c: OverlayViews.Caption, compact: Boolean) {
        c.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 24f)
        c.label.maxLines = if (compact) 3 else Int.MAX_VALUE
        c.label.ellipsize = if (compact) android.text.TextUtils.TruncateAt.END else null
        c.status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 18f)
        val pad = OverlayViews.dp(ctx, if (compact) 8 else 16)
        c.root.setPadding(OverlayViews.dp(ctx, if (compact) 14 else 20), pad, OverlayViews.dp(ctx, if (compact) 14 else 20), pad)
        (c.root.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            val y = OverlayViews.dp(ctx, if (compact) 4 else 40)
            if (lp.y != y) {
                lp.y = y
                added[c.root]?.let { wm -> runCatching { wm.updateViewLayout(c.root, lp) } }
            }
        }
    }

    private fun renderStop(show: Boolean, restack: Boolean) {
        if (!show || restack) { remove(stopChip); stopChip = null }
        if (!show) return
        if (stopChip == null) {
            val v = OverlayViews.stopChip(ctx, s("Stop", "रोको", "Stop")) { container.assistant.stop() }
            if (add(v) { type -> params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, type = type).apply { y = OverlayViews.dp(ctx, 120) } }) stopChip = v
        }
        // Never let the Stop chip cover the button we are pointing at: move it to the other half of the screen.
        val bounds = screen.highlight.value?.bounds
        placeStopChip(atTop = bounds != null && bounds[3] > ctx.resources.displayMetrics.heightPixels * 0.6)
    }

    private fun placeStopChip(atTop: Boolean) {
        val chip = stopChip ?: return
        val wm = added[chip] ?: return
        val lp = chip.layoutParams as? WindowManager.LayoutParams ?: return
        val gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        // Top: just below the instruction card (max ~3 lines). Bottom: above the navigation bar.
        val y = OverlayViews.dp(ctx, if (atTop) 250 else 120)
        if (lp.gravity == gravity && lp.y == y) return
        lp.gravity = gravity
        lp.y = y
        runCatching { wm.updateViewLayout(chip, lp) }
    }

    private fun renderPrompt(p: Prompt?) {
        if (p === shownPrompt) return
        remove(promptView)
        promptView = null
        shownPrompt = null
        when (p) {
            null -> Unit
            is Prompt.Ask -> {
                val (v, input) = OverlayViews.answerCard(ctx, p.question,
                    hint = s("Or type your answer here", "या यहाँ लिखकर जवाब दीजिए", "Ya yahan likh kar jawab dijiye"),
                    ok = s("OK", "ठीक है", "Theek hai"), cancel = s("Cancel", "रद्द करें", "Cancel"),
                    onOk = { text -> if (text.isNotBlank()) p.answer.complete(text.trim()) }, onCancel = { p.answer.complete(null) },
                    level = { container.assistant.voiceLevel() })
                // Focusable so the keyboard can open for people who can't or don't want to speak.
                if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM, focusable = true, type = type).apply {
                        y = OverlayViews.dp(ctx, 24)
                        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    } }) {
                    promptView = v
                    shownPrompt = p
                    input.requestFocus()
                }
            }
            is Prompt.Confirm -> {
                // "It always asks first": a green Yes and a soft grey No. Saying "no" is never dangerous, so it is not red.
                val v = OverlayViews.confirmSheet(ctx, p.summary, s("Yes", "हाँ", "Haan"), s("No", "नहीं", "Nahi"),
                    onYes = { p.answer.complete(true) }, onNo = { p.answer.complete(false) },
                    level = { container.assistant.voiceLevel() })
                if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM, type = type).apply { y = OverlayViews.dp(ctx, 24) } }) {
                    promptView = v
                    shownPrompt = p
                }
            }
        }
    }

    private fun renderResult(r: AssistantScreen.Result?) {
        if (r === shownResult) return
        remove(resultView)
        resultView = null
        shownResult = r
        r ?: return
        val v = OverlayViews.resultCard(ctx, r.message, r.ok)
        val margin = OverlayViews.dp(ctx, 16)
        if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER, touchable = false, type = type).apply { horizontalMargin = 0f; x = 0 } }) {
            v.setPadding(v.paddingLeft + margin, v.paddingTop, v.paddingRight + margin, v.paddingBottom)
            resultView = v
        }
    }

    /**
     * Hides every Saathi overlay for a moment so a full-screen screenshot shows only the app (phones before
     * Android 14, which can't capture a single window). Views stay in place; only their visibility changes.
     */
    fun setHiddenForCapture(hidden: Boolean) = ui {
        added.keys.forEach { it.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    }

    // ---------------------------------------------------------------- scam alert

    /** Full-screen alert. "I'm safe" needs two taps so it can't be dismissed by accident. */
    fun showAlert(alert: ScamAlert): Boolean {
        if (!canDraw()) return false
        ui {
            remove(alertView)
            var safeTaps = 0
            val v = OverlayViews.alertScreen(ctx, alert,
                hangUpLabel = if (alert.inCall) s("Hang up", "फ़ोन काटें", "Phone kaatein") else null,
                safeLabel = s("I'm safe, continue", "मैं सुरक्षित हूँ", "Main safe hoon"),
                onHangUp = {
                    container.scope.launch { container.callGuard.endCall() }
                    remove(alertView); alertView = null
                },
                onSafe = { button ->
                    if (++safeTaps >= 2) { remove(alertView); alertView = null }
                    else button.text = s("Tap again to confirm", "पक्का करने के लिए फिर दबाएँ", "Pakka karne ke liye phir dabaiye")
                })
            if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP, type = type) }) alertView = v
            vibrate()
        }
        return true
    }

    private fun vibrate() {
        val vib = ctx.getSystemService(Vibrator::class.java) ?: return
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 250, 700, 250, 700), -1))
    }

    fun removeAll() = ui {
        scope.cancel()
        added.keys.toList().forEach { remove(it) }
        bubble = null; stopChip = null; caption = null; shownCaption = null; highlight = null
        promptView = null; shownPrompt = null; resultView = null; shownResult = null; alertView = null
    }

    // ---------------------------------------------------------------- window plumbing

    private fun windowManager(): Pair<WindowManager, Int> {
        val a11y = AgentService.instance
        return if (a11y != null) {
            a11y.getSystemService(WindowManager::class.java) to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            ctx.getSystemService(WindowManager::class.java) to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean = true, focusable: Boolean = false, type: Int) =
        WindowManager.LayoutParams(w, h, type,
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun add(view: View, build: (type: Int) -> WindowManager.LayoutParams): Boolean {
        if (!canDraw()) return false
        val (wm, type) = windowManager()
        return runCatching { wm.addView(view, build(type)); added[view] = wm }.isSuccess
    }

    private fun remove(view: View?) {
        view ?: return
        added.remove(view)?.let { wm -> runCatching { wm.removeViewImmediate(view) } }
    }

    private fun ui(block: () -> Unit) = if (Looper.myLooper() == Looper.getMainLooper()) block() else { main.post(block); Unit }
}
