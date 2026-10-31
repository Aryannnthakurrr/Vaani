package com.saathi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.saathi.app.ui.AssistantSheet
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.saathi.app.ui.DigestScreen
import com.saathi.app.ui.HomeScreen
import com.saathi.app.ui.Nav
import com.saathi.app.ui.OnboardingScreen
import com.saathi.app.ui.Permissions
import com.saathi.app.ui.SettingsScreen
import com.saathi.app.ui.theme.SaathiTheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Route requested by a notification or the assistant ("digest", "camera", …). */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always light, so the status and navigation bar icons are always dark.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val c = SaathiApp.container
        // Only a fresh start reads the launch intent: after rotation or a font change it was already handled, and
        // a launch from Recents replays the old intent.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handle(intent)
        OverlayService.start(this)
        // Setup is shown once; permissions the user skipped are asked for when a feature needs them.
        val start = if (c.settings.current.setupDone || Permissions.allGranted(this)) "home" else "onboarding"
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
                // Screens opened by the assistant while Saathi is on screen (see Nav.open).
                LaunchedEffect(nav) { Nav.requests.collect { nav.navigate(it) { launchSingleTop = true } } }
                // Notification channel names follow the app language.
                LaunchedEffect(Unit) { c.settings.flow.map { it.replyStyle }.distinctUntilChanged().collect { c.notifier.createChannels(it) } }
                Box(Modifier.fillMaxSize()) {
                    // Gentle only: a short cross-fade between screens, nothing that slides or bounces.
                    NavHost(nav, startDestination = start,
                        // A new screen rises gently into place; going back, the old one sinks away. Nothing slides sideways.
                        enterTransition = { fadeIn(tween(280, delayMillis = 60)) + slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 18 } },
                        exitTransition = { fadeOut(tween(160)) },
                        popEnterTransition = { fadeIn(tween(260)) },
                        popExitTransition = { fadeOut(tween(200)) + slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 18 } }) {
                        fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
                        // Back leaves [route] only while it is still on top, so a double tap can't pop the screen below too.
                        fun backFrom(route: String): () -> Unit = {
                            if (nav.currentBackStackEntry?.destination?.route == route && !nav.popBackStack()) go("home")
                        }
                        composable("onboarding") {
                            // Re-run from Settings: Back and Start return there. First run: Start replaces setup with Home.
                            val rerun = remember { nav.previousBackStackEntry != null }
                            OnboardingScreen(c, if (rerun) backFrom("onboarding") else null) {
                                if (rerun) nav.popBackStack("onboarding", inclusive = true)
                                else nav.navigate("home") { popUpTo("onboarding") { inclusive = true }; launchSingleTop = true }
                            }
                        }
                        composable("home") { HomeScreen(c) { go(it) } }
                        composable("digest") { DigestScreen(c, backFrom("digest")) { go("clinic_call") } }
                        composable("alerts") { AlertsScreen(c, backFrom("alerts")) }
                        composable("log") { ActivityLogScreen(c, backFrom("log")) }
                        composable("settings") { SettingsScreen(c, backFrom("settings")) { go("onboarding") } }
                        composable("camera") { CameraScreen(c, backFrom("camera")) }
                        composable("clinic_call") { ClinicCallScreen(c, backFrom("clinic_call")) }
                        composable("care") { CareScreen(c, backFrom("care")) }
                        composable("emergency") { EmergencyScreen(c, backFrom("emergency")) }
                    }
                    // The assistant's questions and results inside the app (over other apps the overlays show them).
                    AssistantSheet(c)
                }
            }
        }
    }

    // Inside Saathi the screens have their own mic and the in-app sheet shows the assistant, so the floating windows
    // (bubble, captions, question cards) only show over other apps.
    override fun onResume() {
        super.onResume()
        inForeground = true
        SaathiApp.container.assistant.screen.foreground(true)
    }

    override fun onPause() {
        inForeground = false
        SaathiApp.container.assistant.screen.foreground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Acts on a route or check-in once, then clears them so a later recreate can't replay them. */
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        intent.removeExtra(EXTRA_ROUTE)
        if (intent.action == ACTION_CHECK_IN) {
            intent.action = null
            val c = SaathiApp.container
            c.scope.launch {
                // checkIn() records a heartbeat (the backend then doesn't alert family); it doesn't message anyone.
                c.welfare.checkIn()
                val style = c.settings.current.replyStyle
                c.speaker.say(com.saathi.app.core.voice.tr(style,
                    "Thank you! I have noted that you are okay.", "धन्यवाद! मैंने नोट कर लिया कि आप ठीक हैं।",
                    "Dhanyavaad! Maine note kar liya ki aap theek hain."), style)
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
