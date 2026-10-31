package com.saathi.app.ui

import android.content.Context
import android.content.Intent
import com.saathi.app.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Opens a Saathi screen from code that has no NavController (the assistant, Live tools, care and emergency flows).
 * While Saathi is on screen the request goes straight to the NavHost; only from another app (or with no activity)
 * does it fall back to starting the activity with a route extra.
 */
object Nav {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Collected by [MainActivity] while it is shown; each value is a route to navigate to. */
    val requests: SharedFlow<String> = _requests

    fun open(context: Context, route: String) {
        val direct = MainActivity.inForeground && _requests.subscriptionCount.value > 0 && _requests.tryEmit(route)
        if (!direct) context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route))
    }
}
