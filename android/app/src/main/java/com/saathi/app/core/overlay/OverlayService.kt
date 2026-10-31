package com.saathi.app.core.overlay

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.saathi.app.BuildConfig
import com.saathi.app.R
import com.saathi.app.SaathiApp
import com.saathi.app.core.Notifier
import com.saathi.app.features.welfare.UnlockReceiver

/**
 * Foreground service (type specialUse) that keeps the mic bubble and safety overlays alive, and registers the
 * runtime-only ACTION_USER_PRESENT receiver for the welfare heartbeat (SPEC §5).
 */
class OverlayService : Service() {
    private val unlockReceiver = UnlockReceiver()
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // scripts/scam_demo.sh scenario 4 on emulators without GPay installed.
                DEBUG_SIMULATE_FOREGROUND -> intent.getStringExtra("package")?.let { SaathiApp.container.callGuard.onForegroundPackage(it) }
                // Typed stand-in for speech: adb shell am broadcast -a com.saathi.app.DEBUG_SAY --es text "…" -p com.saathi.app
                DEBUG_SAY -> intent.getStringExtra("text")?.let { SaathiApp.container.assistant.submitText(it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(microphone = false)
        // The controller draws the bubble itself whenever Saathi's own screen is not in front.
        controller = OverlayController(this, SaathiApp.container)
        ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter(DEBUG_SIMULATE_FOREGROUND).apply { addAction(DEBUG_SAY) }
            ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIC_ON -> goForeground(microphone = true)
            ACTION_MIC_OFF -> goForeground(microphone = false)
        }
        return START_STICKY
    }

    /** Switches the service type to microphone while listening (Android 14 rules for background mic). */
    private fun goForeground(microphone: Boolean) {
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(mic=$microphone) refused: ${e.message}")
            if (microphone) goForeground(false)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Notifier.CH_ASSISTANT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(com.saathi.app.core.voice.tr(SaathiApp.container.settings.current.replyStyle,
                "Saathi is here. Tap the green mic button to talk.", "साथी यहाँ है। बात करने के लिए हरा माइक बटन दबाइए।",
                "Saathi yahan hai. Baat karne ke liye green mic button dabaiye."))
            .setOngoing(true)
            .setContentIntent(SaathiApp.container.notifier.openAppIntent())
            .build()

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugReceiver) }
        controller?.removeAll()
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SaathiOverlay"
        private const val NOTIF_ID = 1
        const val ACTION_MIC_ON = "com.saathi.app.MIC_ON"
        const val ACTION_MIC_OFF = "com.saathi.app.MIC_OFF"
        const val DEBUG_SIMULATE_FOREGROUND = "com.saathi.app.DEBUG_SIMULATE_FOREGROUND"
        const val DEBUG_SAY = "com.saathi.app.DEBUG_SAY"

        @Volatile
        var controller: OverlayController? = null
            private set

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, OverlayService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "could not start overlay service: ${it.message}") }
        }
    }
}
