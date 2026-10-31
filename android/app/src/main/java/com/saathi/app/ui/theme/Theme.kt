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
 * Calm and plain for older eyes (palette from our CareCompass app): warm off-white page, white cards, near-black
 * text, one teal for actions. Colour only as soft tints behind small icons, so each action is easy to tell apart
 * without anything loud. Red only for emergencies, green for "done / safe".
 */
val Paper = Color(0xFFFAF8F4)
val Ink = Color(0xFF16202A)
val Muted = Color(0xFF66707B)
val Line = Color(0xFFE6E0D5)
val Soft = Color(0xFFF6F3EC)
val Accent = Color(0xFF0B7A64)
val AccentDark = Color(0xFF08624F)
val Accent50 = Color(0xFFE7F3EF)
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
