package com.saathi.app.core.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.ui.graphics.toArgb

/** The app's keyboard/switch focus colour (ui/theme/Theme.kt Focus): the ring marks "tap here", like focus. */
private val FOCUS = com.saathi.app.ui.theme.Focus.toArgb()

/**
 * Full-screen, touch-transparent layer that draws a pulsing ring around [target] (screen coordinates) or, for
 * scroll instructions, an animated swipe arrow. The view measures its own on-screen origin so status bars and
 * display cutouts never shift the ring away from the real button (SPEC §6.5).
 */
@SuppressLint("ViewConstructor")
class HighlightView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
        color = FOCUS // the app's focus colour (deep teal), not a loud yellow
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14 * density
        color = Color.argb(90, Color.red(FOCUS), Color.green(FOCUS), Color.blue(FOCUS))
    }
    private val scrim = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10 * density
        strokeCap = Paint.Cap.ROUND
        color = FOCUS
    }
    private var phase = 0f
    private val loc = IntArray(2)

    var target: List<Int>? = null
        set(value) { field = value; invalidate() }
    var gesture: String? = null
        set(value) { field = value; invalidate() }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val g = gesture
        if (g != null) {
            drawSwipe(canvas, g)
            return
        }
        val b = target ?: return
        val pad = 10 * density + 8 * density * pulse()
        val r = RectF(b[0] - loc[0] - pad, b[1] - loc[1] - pad, b[2] - loc[0] + pad, b[3] - loc[1] + pad)
        // Dim everything except the target so the eye goes straight to it.
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(r, 24 * density, 24 * density, Path.Direction.CCW)
        }
        canvas.drawPath(path, scrim)
        canvas.drawRoundRect(r, 24 * density, 24 * density, glow)
        canvas.drawRoundRect(r, 24 * density, 24 * density, ring)
    }

    private fun pulse() = (kotlin.math.sin(phase * 2 * Math.PI).toFloat() + 1f) / 2f

    private fun drawSwipe(canvas: Canvas, g: String) {
        val cx = width / 2f
        val span = height * 0.25f
        val up = g != "swipe_down"
        val start = height * 0.65f
        val y = if (up) start - span * phase else height * 0.35f + span * phase
        canvas.drawLine(cx, y + (if (up) 60 else -60) * density, cx, y, arrow)
        val head = 28 * density * if (up) 1 else -1
        canvas.drawLine(cx, y, cx - head, y + head, arrow)
        canvas.drawLine(cx, y, cx + head, y + head, arrow)
    }
}
