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
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns every floating window: mic bubble, caption card, stop chip, highlight ring, confirm sheet and scam alert.
 * Windows are drawn as TYPE_ACCESSIBILITY_OVERLAY when the accessibility service is on (no extra permission and
 * always on top), otherwise as TYPE_APPLICATION_OVERLAY. All calls are marshalled to the main thread.
 */
class OverlayController(private val ctx: Context, private val container: AppContainer) {
    private val main = Handler(Looper.getMainLooper())
    private val added = HashMap<View, WindowManager>()
    private val style get() = container.settings.current.replyStyle

    private var bubble: View? = null
    private var stopChip: View? = null
    private var caption: OverlayViews.Caption? = null
    private var highlight: HighlightView? = null
    private var sheet: View? = null
    private var alertView: View? = null

    fun canDraw(): Boolean = AgentService.instance != null || Settings.canDrawOverlays(ctx)

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

    // ---------------------------------------------------------------- mic bubble

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() = ui {
        if (bubble != null) return@ui
        val v = OverlayViews.micBubble(ctx)
        val size = OverlayViews.dp(ctx, 72)
        var lp: WindowManager.LayoutParams? = null
        val ok = add(v) { type ->
            params(size, size, Gravity.TOP or Gravity.START, type = type).apply {
                x = ctx.resources.displayMetrics.widthPixels - size - OverlayViews.dp(ctx, 12)
                y = ctx.resources.displayMetrics.heightPixels / 2
            }.also { lp = it }
        }
        if (!ok) return@ui
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

    fun hideBubble() = ui { remove(bubble); bubble = null }

    // ---------------------------------------------------------------- caption + stop chip

    /** Shows [text] in large type. [buttonLabel] adds one big action button (e.g. "Done"). */
    fun showCaption(text: String, status: String = "", buttonLabel: String? = null, onButton: (() -> Unit)? = null) = ui {
        val c = caption ?: OverlayViews.Caption(ctx).also { c ->
            val margin = OverlayViews.dp(ctx, 12)
            if (!add(c.root) { type ->
                    params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP, type = type)
                        .apply { y = OverlayViews.dp(ctx, 40); horizontalMargin = 0f }
                }) return@ui
            c.root.setPadding(margin * 2, margin + 4, margin * 2, margin + 4)
            caption = c
        }
        c.label.text = text
        c.label.contentDescription = text
        c.status.text = status
        c.status.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        if (buttonLabel != null && onButton != null) {
            c.button.text = buttonLabel
            c.button.setOnClickListener { onButton() }
            c.button.visibility = View.VISIBLE
        } else {
            c.button.visibility = View.GONE
        }
    }

    fun hideCaption() = ui { caption?.let { remove(it.root) }; caption = null }

    fun showStop(onStop: () -> Unit) = ui {
        if (stopChip != null) return@ui
        val v = OverlayViews.stopChip(ctx, onStop)
        if (add(v) { type -> params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, type = type).apply { y = OverlayViews.dp(ctx, 120) } }) stopChip = v
    }

    fun hideStop() = ui { remove(stopChip); stopChip = null }

    private fun placeStopChip(atTop: Boolean) {
        val chip = stopChip ?: return
        val wm = added[chip] ?: return
        val lp = chip.layoutParams as? WindowManager.LayoutParams ?: return
        lp.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        // Top: just below the instruction card (max ~3 lines). Bottom: above the navigation bar.
        lp.y = OverlayViews.dp(ctx, if (atTop) 250 else 120)
        runCatching { wm.updateViewLayout(chip, lp) }
    }

    // ---------------------------------------------------------------- guide highlight

    fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) = ui {
        val h = highlight ?: HighlightView(ctx).also { hv ->
            if (!add(hv) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START, touchable = false, type = type) }) return@ui
            highlight = hv
        }
        h.gesture = gesture
        h.target = bounds
        // Never let the Stop chip cover the button we are pointing at: move it to the other half of the screen.
        val screenH = ctx.resources.displayMetrics.heightPixels
        placeStopChip(atTop = bounds != null && bounds[3] > screenH * 0.6)
        // Re-add the caption on top of the ring layer, with the instruction in big text.
        caption?.let { remove(it.root) }; caption = null
        showCaption(instruction, tr(style, "Your turn", "अब आप दबाइए", "Ab aap dabaiye"))
    }

    fun clearHighlight() = ui {
        remove(highlight)
        highlight = null
        placeStopChip(atTop = false)
    }

    // ---------------------------------------------------------------- confirm sheet

    /** Shows the confirm sheet; the returned deferred completes with the button answer. */
    fun showConfirm(summary: String): CompletableDeferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        ui {
            remove(sheet)
            val v = OverlayViews.confirmSheet(ctx, summary,
                tr(style, "Yes", "हाँ", "Haan"), tr(style, "No", "नहीं", "Nahi"),
                onYes = { result.complete(true) }, onNo = { result.complete(false) },
                level = { container.assistant.voiceLevel() })
            if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM, type = type).apply { y = OverlayViews.dp(ctx, 24) } }) sheet = v
        }
        result.invokeOnCompletion { ui { remove(sheet); sheet = null } }
        return result
    }

    // ---------------------------------------------------------------- typed answer

    private var answerBox: View? = null

    /**
     * A question card with a text box, for people who can't or don't want to speak. The window is focusable so
     * the keyboard can open; it completes with the typed text, or null on Cancel.
     */
    fun showAnswerBox(question: String): CompletableDeferred<String?> {
        val result = CompletableDeferred<String?>()
        ui {
            remove(answerBox)
            val (v, input) = OverlayViews.answerCard(ctx, question,
                hint = tr(style, "Or type your answer here", "या यहाँ लिखकर जवाब दीजिए", "Ya yahan likh kar jawab dijiye"),
                ok = tr(style, "OK", "ठीक है", "Theek hai"), cancel = tr(style, "Cancel", "रद्द करें", "Cancel"),
                onOk = { text -> if (text.isNotBlank()) result.complete(text.trim()) }, onCancel = { result.complete(null) })
            if (add(v) { type -> params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM, focusable = true, type = type).apply {
                    y = OverlayViews.dp(ctx, 24)
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                } }) answerBox = v
            input.requestFocus()
        }
        result.invokeOnCompletion { ui { remove(answerBox); answerBox = null } }
        return result
    }

    // ---------------------------------------------------------------- scam alert

    /** Full-screen alert. "I'm safe" needs two taps so it can't be dismissed by accident. */
    fun showAlert(alert: ScamAlert): Boolean {
        if (!canDraw()) return false
        ui {
            remove(alertView)
            var safeTaps = 0
            val v = OverlayViews.alertScreen(ctx, alert,
                hangUpLabel = if (alert.inCall) tr(style, "Hang up", "फ़ोन काटें", "Phone kaatein") else null,
                safeLabel = tr(style, "I'm safe, continue", "मैं सुरक्षित हूँ", "Main safe hoon"),
                onHangUp = {
                    container.scope.launch { container.callGuard.endCall() }
                    remove(alertView); alertView = null
                },
                onSafe = { button ->
                    if (++safeTaps >= 2) { remove(alertView); alertView = null }
                    else button.text = tr(style, "Tap again to confirm", "पक्का करने के लिए फिर दबाएँ", "Pakka karne ke liye phir dabaiye")
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
        added.keys.toList().forEach { remove(it) }
        bubble = null; stopChip = null; caption = null; highlight = null; sheet = null; alertView = null
    }
}
