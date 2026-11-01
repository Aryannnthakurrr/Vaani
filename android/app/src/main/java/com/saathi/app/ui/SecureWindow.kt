package com.saathi.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the window FLAG_SECURE while a screen with private data is showing (digest, alerts, the doctor screen with
 * patient details, the activity log, settings): screenshots, screen recording and screen-sharing apps, a common
 * scam tool, see a black screen. Cleared again when the last such screen closes: going from one secure screen to
 * another, the new one starts before the old one is disposed, so a plain clear would unprotect the new one.
 */
@Composable
fun SecureWindow() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        val count = (secureHolders[window] ?: 0) + 1
        secureHolders[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val left = (secureHolders[window] ?: 1) - 1
            if (left <= 0) {
                secureHolders.remove(window)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                secureHolders[window] = left
            }
        }
    }
}

/** How many secure screens each window is showing (main thread only). Weak keys, so a destroyed activity is dropped. */
private val secureHolders = java.util.WeakHashMap<android.view.Window, Int>()
