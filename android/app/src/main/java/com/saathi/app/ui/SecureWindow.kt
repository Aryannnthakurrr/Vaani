package com.saathi.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the window FLAG_SECURE while a screen with private data is showing (digest, alerts, the doctor screen with
 * patient details, the activity log, settings): screenshots, screen recording and screen-sharing apps, a common
 * scam tool, see a black screen. Cleared again when the screen closes.
 */
@Composable
fun SecureWindow() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
