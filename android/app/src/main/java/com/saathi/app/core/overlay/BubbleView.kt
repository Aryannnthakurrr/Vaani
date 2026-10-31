package com.saathi.app.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import com.saathi.app.R
import com.saathi.app.core.VoiceStatus
import kotlin.math.sin

/**
 * The floating mic bubble, drawn by hand so it can show what Saathi is doing:
 * - idle: white disc, mic with a red slash (not listening)
 * - connecting: teal disc, mic, a ring going round
 * - listening: teal disc, mic, a soft ring that grows with the user's voice
 * - speaking: teal disc, bars that move like speech
 * - working: teal disc, a stop square (a tap stops the task), a ring going round
 * Only moving states animate, so the idle bubble costs nothing.
 */
internal class BubbleView(ctx: Context, private val level: () -> Float) : View(ctx) {
    var status: VoiceStatus = VoiceStatus.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = ctx.resources.displayMetrics.density
    private val mic = ContextCompat.getDrawable(ctx, R.drawable.ic_mic)!!.mutate()
    private val micOff = ContextCompat.getDrawable(ctx, R.drawable.ic_mic_off)!!.mutate()
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = OverlayViews.LINE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayViews.ACCENT }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND; color = OverlayViews.ACCENT
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private var smooth = 0f

    init {
        // The soft shadow under the disc needs a software layer; the view is small, so this is cheap.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = DISC_DP * d / 2f
        val t = System.nanoTime() / 1e9f
        smooth += (level().coerceIn(0f, 1f) - smooth) * 0.3f
        val idle = status == VoiceStatus.IDLE

        when (status) {
            VoiceStatus.LISTENING -> {
                // A ring that swells with the voice, and a faint one that breathes even in silence.
                halo.alpha = 50
                canvas.drawCircle(cx, cy, r + (4 + 3 * sin(t * 2.4f)) * d + smooth * 14 * d, halo)
            }
            VoiceStatus.CONNECTING, VoiceStatus.WORKING -> {
                val gap = 5 * d
                oval.set(cx - r - gap, cy - r - gap, cx + r + gap, cy + r + gap)
                canvas.drawArc(oval, (t * 300) % 360, 100f, false, arc)
            }
            else -> Unit
        }

        disc.color = if (idle) Color.WHITE else OverlayViews.ACCENT
        disc.setShadowLayer(6 * d, 0f, 2 * d, Color.argb(70, 0, 0, 0))
        canvas.drawCircle(cx, cy, r, disc)
        if (idle) canvas.drawCircle(cx, cy, r, rim)

        val icon = (ICON_DP * d / 2).toInt()
        when (status) {
            VoiceStatus.IDLE -> micOff.apply { setBounds((cx - icon).toInt(), (cy - icon).toInt(), (cx + icon).toInt(), (cy + icon).toInt()) }.draw(canvas)
            VoiceStatus.SPEAKING -> {
                // Five bars moving like speech.
                white.strokeWidth = 3.5f * d
                for (i in 0 until 5) {
                    val h = (0.35f + 0.65f * ((sin(t * 9f + i * 1.3f) + 1) / 2)) * (if (i == 0 || i == 4) 0.5f else 1f) * r * 0.55f
                    val x = cx + (i - 2) * 7 * d
                    canvas.drawLine(x, cy - h, x, cy + h, white)
                }
            }
            VoiceStatus.WORKING -> {
                val s = 9 * d
                oval.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(oval, 3 * d, 3 * d, white)
            }
            else -> mic.apply { setBounds((cx - icon).toInt(), (cy - icon).toInt(), (cx + icon).toInt(), (cy + icon).toInt()) }.draw(canvas)
        }
        if (!idle) postInvalidateOnAnimation()
    }

    companion object {
        /** The window is larger than the disc so the rings have room. */
        const val WINDOW_DP = 96
        const val DISC_DP = 64
        const val ICON_DP = 30
    }
}
