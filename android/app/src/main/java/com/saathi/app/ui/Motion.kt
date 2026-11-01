package com.saathi.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Motion for older users: soft and quick, never bouncy or long. Everything settles within ~400 ms, and nothing
 * moves on its own except the talking circle.
 */

/** The standard easing for things arriving on screen. */
val Arrive = FastOutSlowInEasing

/**
 * A pressed button sinks a little on a soft spring and the phone gives a light tick, so a tap feels confirmed even
 * for someone who doesn't see the screen change. Returns the interaction source to pass to the button.
 */
@Composable
fun rememberPress(): Pair<MutableInteractionSource, Modifier> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Keyboard, D-pad or switch access: a clear accent ring, since Material 3 shows almost nothing on focus.
    val focused by source.collectIsFocusedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) { if (pressed) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    val still = reducedMotion()
    val scale by animateFloatAsState(
        if (pressed && !still) 0.98f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val ring = with(LocalDensity.current) { com.saathi.app.ui.theme.Border.focus.toPx() }
    val corner = with(LocalDensity.current) { 6.dp.toPx() }
    return source to Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.drawWithContent {
        drawContent()
        if (focused) drawRoundRect(com.saathi.app.ui.theme.Focus, topLeft = Offset(-ring, -ring),
            size = Size(size.width + 2 * ring, size.height + 2 * ring), cornerRadius = CornerRadius(corner),
            style = Stroke(width = ring))
    }
}

/** The phone's "Remove animations" accessibility setting: everything then appears in place, without motion. */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalView.current.context
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Fades in and slides into place the first time it is shown: [order] staggers a list (50 ms apart), [fromY] and
 * [fromX] say where it comes from.
 */
fun Modifier.riseIn(order: Int = 0, fromY: Dp = 18.dp, fromX: Dp = 0.dp): Modifier = composed {
    if (reducedMotion()) return@composed Modifier
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        delay(order * 50L)
        launch { progress.animateTo(1f, tween(380, easing = Arrive)) }
    }
    val dy = with(density) { fromY.toPx() }
    val dx = with(density) { fromX.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1 - p) * dy
        translationX = (1 - p) * dx
    }
}
