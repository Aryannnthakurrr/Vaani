package com.saathi.app.ui.theme

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp

/*
 * Quiet and legible for older eyes: a neutral page, white surfaces with hairline borders, near-black text and a
 * single deep teal for actions. No colour blocks; red only for emergencies, green for "done / safe".
 */
val Paper = Color(0xFFF6F6F3)
val Ink = Color(0xFF14181C)
val Muted = Color(0xFF5E666E)
val Line = Color(0xFFE3E3DF)
/** Edge of tappable cards and buttons: 3:1 against white so every control is clearly outlined. */
val Edge = Color(0xFF8A9096)
val Soft = Color(0xFFEEEEEA)
val Accent = Color(0xFF0E6B5A)
val AccentDark = Color(0xFF0A5445)
val Accent50 = Color(0xFFE4F0EC)
val Leaf = Color(0xFF177245)
val Leaf50 = Color(0xFFE6F4EC)
val Danger = Color(0xFFC42121)
val Danger50 = Color(0xFFFCEBEB)
val Amber = Color(0xFFA35A06)
val Amber50 = Color(0xFFFDF1DF)
val Coral = Color(0xFFC8452A)
val Coral50 = Color(0xFFFCEDE8)
val Sky = Color(0xFF2C5AA0)
val Sky50 = Color(0xFFE8EEF6)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Accent50, onPrimaryContainer = Ink,
    secondary = Leaf, onSecondary = Color.White,
    secondaryContainer = Leaf50, onSecondaryContainer = Ink,
    tertiary = Accent, onTertiary = Color.White,
    error = Danger, onError = Color.White,
    background = Paper, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Soft, onSurfaceVariant = Muted,
    surfaceContainer = Color.White, surfaceContainerLow = Color.White, surfaceContainerHigh = Soft,
    outline = Line, outlineVariant = Line,
)

/**
 * Large type everywhere: body ≥ 22sp, headings ≥ 28sp (spec design principles). sp respects system font size.
 * Simple line breaking: the default "paragraph" strategy balances lines and wrongly splits short Devanagari
 * phrases ("नाम सेव करें") onto two lines.
 */
/** Mukta (Ek Type, OFL): one friendly face for English and Devanagari, so both scripts look like one app. */
private val Mukta = FontFamily(
    Font(R.font.mukta_regular, FontWeight.Normal),
    Font(R.font.mukta_semibold, FontWeight.SemiBold),
    Font(R.font.mukta_bold, FontWeight.Bold),
)

private val BigType = Typography(
    displaySmall = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontFamily = Mukta, lineBreak = LineBreak.Simple, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
)

/** Always the light, white theme: high contrast and familiar, whatever the phone's dark-mode setting. */
@Composable
fun SaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Light, typography = BigType, content = content)
}
