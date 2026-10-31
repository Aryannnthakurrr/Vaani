package com.saathi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.features.calls.ClinicCallScreen
import com.saathi.app.features.care.CareScreen
import com.saathi.app.features.care.EmergencyScreen
import com.saathi.app.features.camera.CameraScreen
import com.saathi.app.ui.ActivityLogScreen
import com.saathi.app.ui.AlertsScreen
import com.saathi.app.ui.DigestScreen
import com.saathi.app.ui.HomeScreen
import com.saathi.app.ui.OnboardingScreen
import com.saathi.app.ui.Permissions
import com.saathi.app.ui.SettingsScreen
import com.saathi.app.ui.theme.SaathiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Route requested by a notification or the assistant ("digest", "camera", …). */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = SaathiApp.container
        handle(intent)
        OverlayService.start(this)
        val start = if (Permissions.allGranted(this)) "home" else "onboarding"
        setContent {
            SaathiTheme {
                val nav = rememberNavController()
                val route = pendingRoute.value
                LaunchedEffect(route) {
                    if (route != null) {
                        nav.navigate(route) { launchSingleTop = true }
                        pendingRoute.value = null
                    }
                }
                NavHost(nav, startDestination = start) {
                    val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate("home") }
                    composable("onboarding") { OnboardingScreen(c) { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } } }
                    composable("home") { HomeScreen(c) { nav.navigate(it) { launchSingleTop = true } } }
                    composable("digest") { DigestScreen(c, back) { nav.navigate("clinic_call") } }
                    composable("alerts") { AlertsScreen(c, back) }
                    composable("log") { ActivityLogScreen(c, back) }
                    composable("settings") { SettingsScreen(c, back) { nav.navigate("onboarding") } }
                    composable("camera") { CameraScreen(c, back) }
                    composable("clinic_call") { ClinicCallScreen(c, back) }
                    composable("care") { CareScreen(c, back) }
                    composable("emergency") { EmergencyScreen(c, back) }
                }
            }
        }
    }

    // Inside Saathi the screens have their own mic, so the floating bubble only shows over other apps.
    override fun onResume() {
        super.onResume()
        inForeground = true
        OverlayService.controller?.hideBubble()
    }

    override fun onPause() {
        inForeground = false
        OverlayService.controller?.showBubble()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        if (intent.action == ACTION_CHECK_IN) {
            val c = SaathiApp.container
            c.scope.launch {
                c.welfare.checkIn()
                c.speaker.say(com.saathi.app.core.voice.tr(c.settings.current.replyStyle,
                    "Thank you! I told your family you are okay.", "धन्यवाद! मैंने आपके परिवार को बता दिया कि आप ठीक हैं।",
                    "Dhanyavaad! Maine family ko bata diya ki aap theek hain."), c.settings.current.replyStyle)
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ACTION_CHECK_IN = "com.saathi.app.CHECK_IN"

        @Volatile
        var inForeground = false
            private set
    }
}
