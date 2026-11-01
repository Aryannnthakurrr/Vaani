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
import androidx.compose.ui.graphics.toArgb

/** Small View builders for overlays. Plain Views (not Compose) keep overlays light inside services. */
internal object OverlayViews {
    // Taken straight from the app's theme (ui/theme/Theme.kt), so the overlays over Uber or Pronto look exactly like
    // the app itself: a white page, black type and black primary actions, thin grey outlines, square-ish corners and
    // no shadows. Green only for yes/done, red only for danger, amber only for caution.
    private fun argb(c: androidx.compose.ui.graphics.Color) = c.toArgb()
    /** Saathi's own colour in overlays (the mic disc, rings, voice waves, OK): the app's primary, black. */
    val ACCENT = argb(com.saathi.app.ui.theme.Ink)
    val GREEN = argb(com.saathi.app.ui.theme.Leaf)
    val GREEN50 = argb(com.saathi.app.ui.theme.Leaf50)
    val RED = argb(com.saathi.app.ui.theme.Danger)
    val AMBER = argb(com.saathi.app.ui.theme.Amber)
    val AMBER50 = argb(com.saathi.app.ui.theme.Amber50)
    val INK = argb(com.saathi.app.ui.theme.Ink)
    val MUTED = argb(com.saathi.app.ui.theme.Muted)
    val PAPER = argb(com.saathi.app.ui.theme.Paper)
    val LINE = argb(com.saathi.app.ui.theme.Line)
    /** Outline of cards, buttons and fields (4.5:1 on white), as in the app. */
    val EDGE = argb(com.saathi.app.ui.theme.Edge)
    val SOFT = argb(com.saathi.app.ui.theme.Soft)

    /** The app's corner radius for cards, buttons and fields. Pills and circles stay round. */
    private const val CORNER_DP = 4

    /** The app's control outline, 1.5dp. */
    private fun outline(ctx: Context) = maxOf(2, (1.5f * ctx.resources.displayMetrics.density).toInt())

    /** The shared card look: white, square-ish, a thin grey outline, no shadow (it stands out over any app by its edge). */
    fun card(ctx: Context) = rounded(PAPER, dp(ctx, CORNER_DP).toFloat(), EDGE, outline(ctx))

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

    /**
     * Text set like the app's type scale (ui/theme/Theme.kt): an exact line height (28sp on 34sp, 22 on 30…) and no
     * font padding. Mukta's own padding plus a line multiplier used to leave big gaps between lines.
     */
    fun text(ctx: Context, s: String, sp: Float, color: Int = INK, bold: Boolean = false, lineSp: Float = lineFor(sp)) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = font(ctx, bold)
            includeFontPadding = false
            lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineSp, ctx.resources.displayMetrics).toInt()
        }

    /** The app's line heights: 28→34, 26→32, 24→30, 22→30, 18→25; others about 1.25×. */
    private fun lineFor(sp: Float): Float = when (sp.toInt()) {
        28 -> 34f; 26 -> 32f; 24 -> 30f; 22 -> 30f; 21 -> 26f; 18 -> 25f
        else -> (sp * 1.25f).let { kotlin.math.round(it) }
    }

    // The app's type roles, for overlays: a question or summary, a title, body text, small helper text.
    const val HEADLINE_SP = 28f
    const val TITLE_SP = 26f
    const val BODY_SP = 22f
    const val SMALL_SP = 18f

    /** The app's BigButton: 64dp tall, 4dp corners, bold 26sp label. */
    fun bigButton(ctx: Context, label: String, bg: Int, onClick: () -> Unit, fg: Int = Color.WHITE) = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        typeface = font(ctx, bold = true)
        includeFontPadding = false
        background = rounded(bg, dp(ctx, CORNER_DP).toFloat())
        minHeight = dp(ctx, 64)
        setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /**
     * The app's assistant sheet (ui/AssistantSheet.kt): white, full width at the bottom of the screen, 28dp top corners,
     * a hairline outline and its shadow. Questions, yes/no and results use it, so they look the same over Uber as in
     * Vaani. The bottom padding clears the navigation bar.
     */
    private fun sheet(ctx: Context, content: LinearLayout): LinearLayout = content.apply {
        orientation = LinearLayout.VERTICAL
        val r = dp(ctx, 28).toFloat()
        background = GradientDrawable().apply {
            setColor(PAPER)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(maxOf(1, dp(ctx, 1)), EDGE)
        }
        elevation = dp(ctx, 16).toFloat()
        setPadding(dp(ctx, 20), dp(ctx, 24), dp(ctx, 20), dp(ctx, 24) + navBarPx(ctx))
    }

    private fun navBarPx(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(ctx, 24)
    }

    /** The app's VoiceWave: thin black bars that move with the voice, and a small grey hint under them. */
    private fun voiceWave(ctx: Context, level: () -> Float, hint: String?): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(VoiceWaveView(ctx, level, ACCENT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44)))
        if (hint != null) addView(text(ctx, hint, SMALL_SP, MUTED).apply { gravity = Gravity.CENTER })
    }

    fun stopChip(ctx: Context, label: String, onStop: () -> Unit) = Button(ctx).apply {
        text = "■  $label"
        isAllCaps = false
        setTextColor(RED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = font(ctx, bold = true)
        // Clear but not alarming: white with red text, like the app's quiet buttons.
        background = rounded(Color.WHITE, dp(ctx, 40).toFloat(), RED, dp(ctx, 2))
        setPadding(dp(ctx, 28), dp(ctx, 12), dp(ctx, 28), dp(ctx, 12))
        minHeight = dp(ctx, 64)
        contentDescription = label
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
        level: () -> Float = { 0f }, hint: String? = null,
    ): View {
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        // As the app's ConfirmCard: the summary, the voice wave with its hint, a green Yes and a grey No.
        return sheet(ctx, LinearLayout(ctx)).apply {
            addView(text(ctx, summary, HEADLINE_SP, INK, bold = true).apply {
                contentDescription = summary
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            addView(voiceWave(ctx, level, hint), full(16))
            addView(bigButton(ctx, yes, GREEN, onYes), full(16))
            addView(bigButton(ctx, no, SOFT, onNo, fg = INK), full(16))
        }
    }

    /**
     * The end of a task, as the app's ResultCard: a 64dp green tick (or amber "!" when it could not be done) beside
     * one sentence ("Uber Go booked, arriving in 4 min").
     */
    fun resultCard(ctx: Context, message: String, ok: Boolean): View = sheet(ctx, LinearLayout(ctx)).apply {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(text(ctx, if (ok) "✓" else "!", 32f, if (ok) GREEN else AMBER, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok) GREEN50 else AMBER50) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 64)))
            addView(text(ctx, message, TITLE_SP, INK, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 16) })
        }
        addView(row)
    }

    /** Question card with a text field and OK/Cancel; returns the root and the field. */
    fun answerCard(
        ctx: Context, question: String, hint: String, ok: String, cancel: String,
        onOk: (String) -> Unit, onCancel: () -> Unit, level: () -> Float = { 0f }, waveHint: String? = null,
    ): Pair<View, EditText> {
        val input = EditText(ctx).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
            typeface = font(ctx, bold = false)
            setTextColor(INK)
            setHintTextColor(MUTED)
            minHeight = dp(ctx, 64)
            background = rounded(Color.WHITE, dp(ctx, CORNER_DP).toFloat(), EDGE, outline(ctx))
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { v, _, _ -> onOk(v.text.toString()); true }
        }
        // As the app's AskCard: OK (black, the primary) and Cancel (grey) side by side, 12dp apart.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton(ctx, ok, ACCENT, { onOk(input.text.toString()) }),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(ctx, 6) })
            addView(bigButton(ctx, cancel, SOFT, onCancel, fg = INK),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 6) })
        }
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        val root = sheet(ctx, LinearLayout(ctx)).apply {
            addView(text(ctx, question, HEADLINE_SP, INK, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            // Saathi is listening for a spoken answer too, not only the text box.
            addView(voiceWave(ctx, level, waveHint), full(16))
            addView(input, full(16))
            addView(row, full(16))
        }
        return root to input
    }

    private val SOFT_WHITE = Color.argb(225, 255, 255, 255)

    /** Alert text: white by default, on the red or amber card. */
    private fun alertText(ctx: Context, s: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        text(ctx, s, sp, color, bold)

    /**
     * Scam alert for a message: a red card at the bottom, so the app (and the message in it) stays visible above.
     * Touches outside the card reach the app. Only the button looks like a button: who sent it is a plain line
     * under the title.
     */
    fun alertCard(ctx: Context, alert: ScamAlert, safeLabel: String, onSafe: (TextView) -> Unit): View {
        val bg = if (alert.level == AlertLevel.RED) RED else AMBER
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(alertText(ctx, "⚠️", 30f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(alertText(ctx, alert.title, 26f, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 12) })
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, dp(ctx, CORNER_DP).toFloat())
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 20))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            addView(header)
            alert.from?.let { addView(alertText(ctx, it, 19f, SOFT_WHITE), full(6)) }
            // A thin line between "what and who" and "what to do".
            addView(View(ctx).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply { topMargin = dp(ctx, 16) })
            addView(alertText(ctx, alert.body, 21f), full(16))
            val safe = bigButton(ctx, safeLabel, Color.WHITE, {}, fg = bg).apply { minHeight = dp(ctx, 68) }
            safe.setOnClickListener { onSafe(safe) }
            addView(safe, full(16))
        }
        return android.widget.FrameLayout(ctx).apply {
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            addView(card)
        }
    }

    /**
     * "Do you want to turn on call forwarding?": the dialer stays visible but dimmed behind (and can't be tapped),
     * the question on a card at the bottom. A soft grey Yes, and the green No (the safe answer) last, where a habitual
     * tap on the dialpad or Call button would land.
     */
    fun dialCheck(ctx: Context, question: String, body: String, no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit): View {
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) } }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PAPER, dp(ctx, CORNER_DP).toFloat(), AMBER, dp(ctx, 3))
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 20))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            addView(alertText(ctx, "⚠️  $question", 26f, INK, bold = true))
            addView(alertText(ctx, body, 21f, INK), full(12))
            addView(bigButton(ctx, yes, SOFT, onYes, fg = INK).apply { minHeight = dp(ctx, 64) }, full(18))
            addView(bigButton(ctx, no, GREEN, onNo), full(10))
        }
        return android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isClickable = true // the dimmed dialer behind can't be tapped
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 36))
            addView(card, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    /** Full-screen red scam alert, for calls: the person has to look away from the call screen and act. */
    fun alertScreen(
        ctx: Context, alert: ScamAlert, hangUpLabel: String?, safeLabel: String,
        onHangUp: () -> Unit, onSafe: (TextView) -> Unit,
    ): View {
        val bg = if (alert.level == AlertLevel.RED) RED else AMBER
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(ctx, 24), dp(ctx, 48), dp(ctx, 24), dp(ctx, 48))
            addView(text(ctx, "⚠️", 72f, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(text(ctx, alert.title, 34f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
            alert.from?.let {
                addView(alertText(ctx, it, 22f, SOFT_WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(ctx, 10), 0, 0) })
            }
            addView(text(ctx, alert.body, 24f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 16), 0, dp(ctx, 32))
            })
            val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 14) }
            if (hangUpLabel != null) addView(bigButton(ctx, "📞  $hangUpLabel", INK, onHangUp), full)
            val safe = bigButton(ctx, safeLabel, Color.TRANSPARENT, {}).apply {
                background = rounded(Color.argb(40, 255, 255, 255), dp(ctx, CORNER_DP).toFloat(), Color.WHITE, dp(ctx, 3))
            }
            safe.setOnClickListener { onSafe(safe) }
            addView(safe, full)
        }
    }
}
