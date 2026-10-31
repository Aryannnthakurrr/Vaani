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
    val SAFFRON = Color.rgb(224, 102, 43)
    val GREEN = Color.rgb(27, 127, 59)
    val RED = Color.rgb(198, 40, 40)
    val INK = Color.rgb(28, 27, 26)

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
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    fun bigButton(ctx: Context, label: String, bg: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(bg, dp(ctx, 22).toFloat())
        minHeight = dp(ctx, 80)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /** 72dp round mic bubble. */
    fun micBubble(ctx: Context) = TextView(ctx).apply {
        text = "🎤"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SAFFRON)
            setStroke(dp(ctx, 3), Color.WHITE)
        }
        elevation = dp(ctx, 8).toFloat()
        contentDescription = "Saathi. Tap to talk. Long press to open Saathi."
    }

    fun stopChip(ctx: Context, onStop: () -> Unit) = Button(ctx).apply {
        text = "■  Stop / रोको"
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(RED, dp(ctx, 40).toFloat(), Color.WHITE, dp(ctx, 3))
        setPadding(dp(ctx, 28), dp(ctx, 12), dp(ctx, 28), dp(ctx, 12))
        minHeight = dp(ctx, 64)
        contentDescription = "Stop Saathi"
        setOnClickListener { onStop() }
    }

    /** Caption card: big readable text of whatever Saathi is saying, plus an optional action button. */
    class Caption(ctx: Context) {
        val label: TextView = text(ctx, "", 24f, INK, bold = true)
        val status: TextView = text(ctx, "", 18f, SAFFRON, bold = true)
        val button: Button = bigButton(ctx, "", GREEN) {}.apply { visibility = View.GONE }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(ctx, 24).toFloat(), SAFFRON, dp(ctx, 3))
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
            elevation = dp(ctx, 10).toFloat()
            addView(status)
            addView(label)
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 12) })
        }
    }

    /** Bottom confirm sheet with two huge buttons. */
    fun confirmSheet(
        ctx: Context, summary: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit,
        level: () -> Float = { 0f },
    ): View {
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(ctx, 6), 0, dp(ctx, 6), 0) } }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton(ctx, "✅  $yes", GREEN, onYes), lp())
            addView(bigButton(ctx, "❌  $no", RED, onNo), lp())
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(ctx, 28).toFloat(), SAFFRON, dp(ctx, 4))
            setPadding(dp(ctx, 20), dp(ctx, 24), dp(ctx, 20), dp(ctx, 24))
            elevation = dp(ctx, 16).toFloat()
            addView(text(ctx, summary, 26f, INK, bold = true).apply { contentDescription = summary })
            // Moves with the user's voice: say "haan" or "nahi", or tap.
            addView(VoiceWaveView(ctx, level, SAFFRON), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 56))
                .apply { topMargin = dp(ctx, 12) })
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 12) })
        }
    }

    /** Question card with a text field and OK/Cancel; returns the root and the field. */
    fun answerCard(
        ctx: Context, question: String, hint: String, ok: String, cancel: String,
        onOk: (String) -> Unit, onCancel: () -> Unit,
    ): Pair<View, EditText> {
        val input = EditText(ctx).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(INK)
            minHeight = dp(ctx, 72)
            background = rounded(Color.rgb(253, 247, 238), dp(ctx, 16).toFloat(), SAFFRON, dp(ctx, 2))
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { v, _, _ -> onOk(v.text.toString()); true }
        }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(ctx, 6), 0, dp(ctx, 6), 0) } }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton(ctx, ok, GREEN) { onOk(input.text.toString()) }, lp())
            addView(bigButton(ctx, cancel, Color.rgb(110, 98, 90), onCancel), lp())
        }
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(ctx, 28).toFloat(), SAFFRON, dp(ctx, 4))
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            elevation = dp(ctx, 16).toFloat()
            addView(text(ctx, question, 22f, INK, bold = true))
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
            val safe = bigButton(ctx, safeLabel, Color.TRANSPARENT) {}.apply {
                background = rounded(Color.argb(40, 255, 255, 255), dp(ctx, 22).toFloat(), Color.WHITE, dp(ctx, 3))
            }
            safe.setOnClickListener { onSafe(safe) }
            addView(safe, full)
        }
    }
}
