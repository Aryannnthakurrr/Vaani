package com.saathi.app.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small View builders for overlays. Plain Views (not Compose) keep overlays light inside services. */
internal object OverlayViews {
    // Same calm palette as the app (ui/theme/Theme.kt): teal for Saathi, green for yes, soft grey for no, red only
    // for danger, warm off-white cards with a thin warm border.
    val ACCENT = Color.rgb(11, 122, 100)
    val GREEN = Color.rgb(23, 114, 69)
    val GREEN50 = Color.rgb(230, 244, 236)
    val RED = Color.rgb(196, 33, 33)
    val INK = Color.rgb(22, 32, 42)
    val MUTED = Color.rgb(102, 112, 123)
    val PAPER = Color.rgb(250, 248, 244)
    val LINE = Color.rgb(230, 224, 213)
    val SOFT = Color.rgb(238, 233, 223)

    /** The shared card look: off-white, rounded, a thin warm border and a soft shadow. */
    fun card(ctx: Context) = rounded(PAPER, dp(ctx, 28).toFloat(), LINE, dp(ctx, 1))

    /** Mukta, the app's font (ui/theme/Theme.kt), so overlays read like the app. */
    fun font(ctx: Context, bold: Boolean): Typeface? = runCatching {
        androidx.core.content.res.ResourcesCompat.getFont(ctx, if (bold) com.saathi.app.R.font.mukta_bold else com.saathi.app.R.font.mukta_regular)
    }.getOrNull() ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (stroke != null) setStroke(strokePx, stroke)
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int = INK, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(ctx, bold)
        setLineSpacing(0f, 1.15f)
    }

    fun bigButton(ctx: Context, label: String, bg: Int, onClick: () -> Unit, fg: Int = Color.WHITE) = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = font(ctx, bold = true)
        background = rounded(bg, dp(ctx, 22).toFloat())
        minHeight = dp(ctx, 80)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /** 72dp round mic bubble. */
    fun micBubble(ctx: Context) = android.widget.ImageView(ctx).apply {
        setImageResource(com.saathi.app.R.drawable.ic_mic)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
            setStroke(dp(ctx, 3), Color.WHITE)
        }
        elevation = dp(ctx, 8).toFloat()
        contentDescription = "Saathi. Tap to talk. Long press to open Saathi."
    }

    fun stopChip(ctx: Context, onStop: () -> Unit) = Button(ctx).apply {
        text = "■  Stop / रोको"
        isAllCaps = false
        setTextColor(RED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = font(ctx, bold = true)
        // Clear but not alarming: white with red text, like the app's quiet buttons.
        background = rounded(Color.WHITE, dp(ctx, 40).toFloat(), RED, dp(ctx, 2))
        elevation = dp(ctx, 8).toFloat()
        setPadding(dp(ctx, 28), dp(ctx, 12), dp(ctx, 28), dp(ctx, 12))
        minHeight = dp(ctx, 64)
        contentDescription = "Stop Saathi"
        setOnClickListener { onStop() }
    }

    /** Caption card: big readable text of whatever Saathi is saying, plus an optional action button. */
    class Caption(ctx: Context) {
        val label: TextView = text(ctx, "", 24f, INK, bold = true)
        val status: TextView = text(ctx, "", 18f, ACCENT, bold = true)
        val button: Button = bigButton(ctx, "", GREEN, {}).apply { visibility = View.GONE }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(ctx)
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
            elevation = dp(ctx, 10).toFloat()
            addView(status)
            addView(label)
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 12) })
        }
    }

    /**
     * "It always asks first": one card with the short summary ("Uber Go to AIIMS, ₹240"), then a green Yes and a
     * soft grey No, one per row. Saying "no" is never dangerous, so it is not red.
     */
    fun confirmSheet(
        ctx: Context, summary: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit,
        level: () -> Float = { 0f },
    ): View {
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(ctx)
            setPadding(dp(ctx, 22), dp(ctx, 24), dp(ctx, 22), dp(ctx, 22))
            elevation = dp(ctx, 16).toFloat()
            addView(text(ctx, summary, 26f, INK, bold = true).apply { contentDescription = summary })
            // Moves with the user's voice: say "haan" or "nahi", or tap.
            addView(VoiceWaveView(ctx, level, ACCENT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48))
                .apply { topMargin = dp(ctx, 10) })
            addView(bigButton(ctx, yes, GREEN, onYes), full(12))
            addView(bigButton(ctx, no, SOFT, onNo, fg = INK), full(12))
        }
    }

    /** The end of a task: a large green tick and one sentence ("Uber Go booked, arriving in 4 min"). */
    fun successCard(ctx: Context, message: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = card(ctx)
        setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 28))
        elevation = dp(ctx, 16).toFloat()
        addView(text(ctx, "✓", 56f, GREEN, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN50) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(ctx, 112), dp(ctx, 112)))
        addView(text(ctx, message, 24f, INK, bold = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 16) })
    }

    /** Question card with a text field and OK/Cancel; returns the root and the field. */
    fun answerCard(
        ctx: Context, question: String, hint: String, ok: String, cancel: String,
        onOk: (String) -> Unit, onCancel: () -> Unit, level: () -> Float = { 0f },
    ): Pair<View, EditText> {
        val input = EditText(ctx).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(INK)
            minHeight = dp(ctx, 72)
            background = rounded(Color.WHITE, dp(ctx, 16).toFloat(), LINE, dp(ctx, 2))
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { v, _, _ -> onOk(v.text.toString()); true }
        }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(ctx, 6), 0, dp(ctx, 6), 0) } }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton(ctx, ok, ACCENT, { onOk(input.text.toString()) }), lp())
            addView(bigButton(ctx, cancel, SOFT, onCancel, fg = INK), lp())
        }
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(ctx)
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            elevation = dp(ctx, 16).toFloat()
            addView(text(ctx, question, 22f, INK, bold = true))
            // Saathi is listening for a spoken answer too, not only the text box.
            addView(VoiceWaveView(ctx, level, ACCENT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44))
                .apply { topMargin = dp(ctx, 8) })
            addView(input, full(14))
            addView(row, full(14))
        }
        return root to input
    }

    /** Full-screen red scam alert. */
    fun alertScreen(
        ctx: Context, alert: ScamAlert, hangUpLabel: String?, safeLabel: String,
        onHangUp: () -> Unit, onSafe: (TextView) -> Unit,
    ): View {
        val bg = if (alert.level == AlertLevel.RED) RED else Color.rgb(176, 110, 0)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(ctx, 24), dp(ctx, 48), dp(ctx, 24), dp(ctx, 48))
            addView(text(ctx, "⚠️", 72f, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(text(ctx, alert.title, 34f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(ctx, alert.body, 24f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 16), 0, dp(ctx, 32))
            })
            val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 14) }
            if (hangUpLabel != null) addView(bigButton(ctx, "📞  $hangUpLabel", INK, onHangUp), full)
            val safe = bigButton(ctx, safeLabel, Color.TRANSPARENT, {}).apply {
                background = rounded(Color.argb(40, 255, 255, 255), dp(ctx, 22).toFloat(), Color.WHITE, dp(ctx, 3))
            }
            safe.setOnClickListener { onSafe(safe) }
            addView(safe, full)
        }
    }
}
