package com.saathi.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.saathi.app.R
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Minimalism & Swiss style, for older eyes: a white page, black type, black primary actions, thin grey rules and
 * square-ish corners, no shadows and no gradients. One accent (deep teal) marks selection, focus and "live"; red is
 * only for danger and stop, green only for done/safe, amber only for caution. Contrast on white: Ink 18:1, Muted
 * 7.5:1, Edge 4.5:1 (control outlines), every status colour ≥ 4.5:1.
 */
val Paper = Color(0xFFFFFFFF)
val Ink = Color(0xFF111111)
val Muted = Color(0xFF545454)
/** Dividers and quiet rules between groups. */
val Line = Color(0xFFE0E0E0)
/** Edge of tappable cards, buttons and fields: 4.5:1 against white, so every control is clearly outlined. */
val Edge = Color(0xFF767676)
/** Quiet panels inside a card (the "how family joins" box, the offline switch). */
val Soft = Color(0xFFF2F2F2)
/** The single accent: selection, keyboard focus, "live". */
val Accent = Color(0xFF0E6B5A)
val AccentDark = Color(0xFF0A5445)
val Accent50 = Color(0xFFE6F0EE)
val Leaf = Color(0xFF177245)
val Leaf50 = Color(0xFFE8F3EC)
val Danger = Color(0xFFC42121)
val Danger50 = Color(0xFFFBECEC)
val Amber = Color(0xFF9A5505)
val Amber50 = Color(0xFFFBF1E3)
/** Category colours in the updates list (bills, renewals); kept as they are, used as small marks only. */
val Coral = Color(0xFFB53D24)
val Coral50 = Color(0xFFFBEDE9)
val Sky = Color(0xFF2C5AA0)
val Sky50 = Color(0xFFEBF0F7)
/** Text and icons on a filled primary, accent or status surface. */
val OnFill = Color.White
/** The keyboard/switch-access focus ring. */
val Focus = Accent

private val Light = lightColorScheme(
    primary = Ink, onPrimary = OnFill,
    primaryContainer = Soft, onPrimaryContainer = Ink,
    secondary = Leaf, onSecondary = OnFill,
    secondaryContainer = Leaf50, onSecondaryContainer = Ink,
    tertiary = Accent, onTertiary = OnFill,
    tertiaryContainer = Accent50, onTertiaryContainer = Ink,
    error = Danger, onError = OnFill,
    errorContainer = Danger50, onErrorContainer = Danger,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Soft, onSurfaceVariant = Muted,
    surfaceContainerLowest = Paper, surfaceContainerLow = Paper, surfaceContainer = Paper,
    surfaceContainerHigh = Soft, surfaceContainerHighest = Soft,
    outline = Edge, outlineVariant = Line,
    scrim = Color(0x99000000),
)

/** Square-ish corners (Swiss): 2dp for small marks, 4dp for cards, buttons and fields. Pills and circles stay round. */
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/** The spacing scale: every gap and padding is one of these (4dp base). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    /** Page gutter. */
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Line weights: rules, control outlines, the focus ring. No shadows anywhere (Swiss). */
object Border {
    val hairline = 1.dp
    val control = 1.5.dp
    val focus = 3.dp
}

/** Minimum touch heights for older hands. */
object Touch {
    val min = 56.dp
    val button = 64.dp
    val primary = 72.dp
}

/**
 * Large type everywhere: body ≥ 22sp, headings ≥ 28sp (spec design principles). sp respects system font size.
 * Simple line breaking: the default "paragraph" strategy balances lines and wrongly splits short Devanagari
 * phrases ("नाम सेव करें") onto two lines. Swiss hierarchy: bold, slightly tightened headings; regular body;
 * secondary lines (Hindi under English, meta) in the small styles, never below 16sp.
 */
/** Mukta (Ek Type, OFL): one clean grotesque-like face for English and Devanagari, so both scripts look like one app. */
private val Mukta = FontFamily(
    Font(R.font.mukta_regular, FontWeight.Normal),
    Font(R.font.mukta_semibold, FontWeight.SemiBold),
    Font(R.font.mukta_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = size.sp, lineHeight = line.sp,
        fontWeight = weight, letterSpacing = tracking.sp)

private val BigType = Typography(
    displaySmall = style(36, 42, FontWeight.Bold, -0.5),
    headlineLarge = style(32, 38, FontWeight.Bold, -0.5),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.25),
    titleLarge = style(26, 32, FontWeight.Bold),
    titleMedium = style(24, 30, FontWeight.SemiBold),
    /** Tile labels and compact titles. */
    titleSmall = style(21, 26, FontWeight.SemiBold),
    bodyLarge = style(22, 30),
    bodyMedium = style(22, 30),
    /** Second-language lines, helper text, meta (time, IDs). */
    bodySmall = style(18, 25),
    labelLarge = style(22, 28, FontWeight.SemiBold),
    /** Pills, small buttons. */
    labelMedium = style(17, 22, FontWeight.Bold),
    /** Badges and counters. */
    labelSmall = style(16, 20, FontWeight.Bold),
)

/** Always the light, white theme: high contrast and familiar, whatever the phone's dark-mode setting. */
@Composable
fun SaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Light, typography = BigType, shapes = SquareShapes, content = content)
}
