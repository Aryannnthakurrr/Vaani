package com.saathi.app.features.scam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.saathi.app.SaathiApp

/**
 * PHONE_STATE broadcasts arrive twice per change on modern Android: once without the number and once with
 * it (READ_CALL_LOG). CallGuard keeps whichever number it has seen.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        SaathiApp.container.callGuard.onCallStateChanged(state, number)
    }
}
