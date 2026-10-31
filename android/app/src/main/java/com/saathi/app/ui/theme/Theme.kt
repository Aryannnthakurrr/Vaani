package com.saathi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Saffron = Color(0xFFE0662B)
val SaffronDark = Color(0xFF9C3D12)
val Leaf = Color(0xFF1B7F3B)
val Danger = Color(0xFFC62828)
val Amber = Color(0xFFF2A900)

private val Light = lightColorScheme(
    primary = SaffronDark, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF3A1300),
    secondary = Leaf, onSecondary = Color.White,
    error = Danger,
    background = Color(0xFFFDF7EE), onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFF3E6D8), onSurfaceVariant = Color(0xFF3B2F27),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB68F), onPrimary = Color(0xFF3A1300),
    primaryContainer = Color(0xFF7A2E0A), onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFF8BD89D), onSecondary = Color(0xFF00391A),
    error = Color(0xFFFF8A80),
    background = Color(0xFF15120F), onBackground = Color(0xFFF2EAE3),
    surface = Color(0xFF221D19), onSurface = Color(0xFFF2EAE3),
    surfaceVariant = Color(0xFF3B2F27), onSurfaceVariant = Color(0xFFE7D7C9),
)

/**
 * Large type everywhere: body ≥ 22sp, headings ≥ 28sp (spec design principles). sp respects system font size.
 * Simple line breaking: the default "paragraph" strategy balances lines and wrongly splits short Devanagari
 * phrases ("नाम सेव करें") onto two lines.
 */
private val BigType = Typography(
    displaySmall = TextStyle(lineBreak = LineBreak.Simple, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
    headlineLarge = TextStyle(lineBreak = LineBreak.Simple, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(lineBreak = LineBreak.Simple, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleLarge = TextStyle(lineBreak = LineBreak.Simple, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleMedium = TextStyle(lineBreak = LineBreak.Simple, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    bodyLarge = TextStyle(lineBreak = LineBreak.Simple, fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(lineBreak = LineBreak.Simple, fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(lineBreak = LineBreak.Simple, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = BigType, content = content)
}
