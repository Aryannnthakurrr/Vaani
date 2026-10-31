package com.saathi.app.features.inbox

import android.content.BroadcastReceiver
import com.saathi.app.SaathiApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Runs suspend work from a BroadcastReceiver, keeping the broadcast alive (up to ~9 s) with goAsync(). */
fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    SaathiApp.container.scope.launch {
        try {
            withTimeoutOrNull(9_000) { block() }
        } finally {
            pending.finish()
        }
    }
}
