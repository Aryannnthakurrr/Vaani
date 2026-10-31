package com.saathi.app.features.care

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * The phone's own number, for the hospital booking, so the user isn't asked to read it out. Many Indian SIMs don't
 * store their number; then this is null and the flow asks instead. The number never leaves the phone except in the
 * booking request itself.
 */
object DevicePhone {
    @SuppressLint("MissingPermission", "HardwareIds") // checked by allowed()
    fun number(context: Context): String? {
        if (!allowed(context)) return null
        val fromSubscription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val ids = sm.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
            val preferred = SubscriptionManager.getDefaultSubscriptionId().takeIf { it in ids } ?: ids.firstOrNull()
            preferred?.let { sm.getPhoneNumber(it) }
        }.getOrNull() else null
        val raw = fromSubscription?.ifBlank { null }
            ?: runCatching { context.getSystemService(TelephonyManager::class.java).line1Number }.getOrNull()
        return normalize(raw)
    }

    /** "+91 74289 12104" → "+917428912104"; null unless it has at least 10 digits. */
    fun normalize(raw: String?): String? {
        val kept = raw?.filter { it.isDigit() || it == '+' } ?: return null
        return kept.takeIf { it.count(Char::isDigit) >= 10 }
    }

    private fun allowed(context: Context) = listOf(Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
