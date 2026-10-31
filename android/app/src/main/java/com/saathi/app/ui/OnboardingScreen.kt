package com.saathi.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Leaf
import kotlinx.coroutines.launch

/** One permission per screen, each explained aloud, with a ✅ re-checked on resume (SPEC §6.8). */
@Composable
fun OnboardingScreen(c: AppContainer, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    val steps = Permissions.steps
    var index by remember { mutableIntStateOf(-1) } // -1 = welcome/language page
    var refresh by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(false) }

    LifecycleResumeEffect(index) {
        refresh++
        onPauseOrDispose { }
    }
    val step = steps.getOrNull(index)
    LaunchedEffect(index, refresh) { granted = step?.granted?.invoke(ctx) ?: true }
    LaunchedEffect(index) {
        val line = step?.let { "${it.title(style)}. ${it.why(style)}" }
            ?: tr(style, "Namaste! I am Saathi. Let's get your phone ready. Choose your language.",
                "नमस्ते! मैं साथी हूँ। चलिए फ़ोन तैयार करते हैं। अपनी भाषा चुनिए।",
                "Namaste! Main Saathi hoon. Chaliye phone ready karte hain. Apni bhasha chuniye.")
        c.speaker.say(line, style)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    SaathiScaffold(tr(style, "Setup", "सेटअप", "Setup"), onBack = null) {
        LinearProgressIndicator(
            progress = { (index + 1f) / (steps.size + 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(10.dp).clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
            drawStopIndicator = {},
        )
        if (index >= 0) {
            Text(tr(style, "Step ${index + 1} of ${steps.size}", "चरण ${index + 1} / ${steps.size}", "Step ${index + 1} / ${steps.size}"),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (step == null) {
            Text("🙏", fontSize = 96.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(tr(style, "Welcome to Saathi", "साथी में आपका स्वागत है", "Saathi mein aapka swagat hai"),
                style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            listOf("en" to "English", "hi" to "हिन्दी", "hinglish" to "Hinglish").forEach { (code, label) ->
                BigButton(label, { scope.launch { c.settings.setLanguage(code); index = 0 } })
            }
            return@SaathiScaffold
        }

        Text(step.emoji, fontSize = 96.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(step.title(style), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(step.why(style), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        if (granted) {
            InfoCard(title = tr(style, "Done", "हो गया", "Ho gaya"),
                body = tr(style, "Tap Next to continue.", "आगे बढ़ने के लिए आगे दबाइए।", "Aage badhne ke liye Aage dabaiye."),
                accent = Leaf, icon = "✅")
        } else {
            BigButton(tr(style, "Allow", "अनुमति दें", "Allow karein"), {
                if (step.runtime.isNotEmpty()) launcher.launch(step.runtime.toTypedArray())
                else step.settingsIntent?.let { ctx.startActivity(it(ctx)) }
            })
            if (step.id == "accessibility" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                InfoCard(
                    title = tr(style, "Is the switch grey?", "क्या बटन धुंधला है?", "Kya button grey hai?"),
                    body = tr(style, "Open App info, tap the three dots at the top right, then tap \"Allow restricted settings\". Then come back here.",
                        "ऐप जानकारी खोलिए, ऊपर दाईं ओर तीन बिंदु दबाइए, फिर \"Allow restricted settings\" दबाइए। फिर यहाँ लौटिए।",
                        "App info kholiye, upar right mein teen dots dabaiye, phir \"Allow restricted settings\" dabaiye. Phir yahan lautiye."),
                ) {
                    BigButton(tr(style, "Open App info", "ऐप जानकारी खोलें", "App info kholein"),
                        { ctx.startActivity(Permissions.restrictedSettingsIntent(ctx)) }, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(tr(style, "Back", "पीछे", "Peeche"), { index-- }, Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            BigButton(if (index == steps.lastIndex) tr(style, "Finish", "पूरा करें", "Finish") else tr(style, "Next", "आगे", "Aage"),
                { if (index == steps.lastIndex) onFinished() else index++ }, Modifier.weight(1f))
        }
    }
}

