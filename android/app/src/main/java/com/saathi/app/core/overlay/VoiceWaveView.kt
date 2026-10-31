package com.saathi.app.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.saathi.app.core.voice.VoiceLevel

/**
 * A row of bars that moves with the user's voice: on a decision card it shows Saathi is listening for a spoken
 * yes or no, so the buttons are not the only way to answer. Purely decorative for TalkBack (the card says it).
 */
class VoiceWaveView(context: Context, private val level: () -> Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }
    private var phase = 0f
    private var smooth = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smooth += (level() - smooth) * 0.35f
        phase += 0.25f
        val heights = VoiceLevel.bars(smooth, phase, BARS)
        val gap = width / (BARS * 2f)
        paint.strokeWidth = gap * 1.1f
        val mid = height / 2f
        heights.forEachIndexed { i, h ->
            val x = gap + i * 2 * gap
            val half = h * height / 2f
            canvas.drawLine(x, mid - half, x, mid + half, paint)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 13
    }
}
