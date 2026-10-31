package com.saathi.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import kotlinx.coroutines.launch

/**
 * First-run setup on one screen (SPEC §6.8): the language, then every permission as a row the user can allow or
 * leave. "Start" always works; anything skipped is asked for later, when a feature needs it.
 */
@Composable
fun OnboardingScreen(c: AppContainer, onBack: (() -> Unit)? = null, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    // Bumped on resume and after a permission dialog, so the ✓ marks re-check what the user just allowed.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Android stops showing a permission's dialog once the user has said no twice; those rows open App info instead.
    var asking by remember { mutableStateOf<PermissionStep?>(null) }
    var blocked by remember { mutableStateOf(emptySet<String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        val step = asking
        asking = null
        val activity = ctx.findActivity()
        // Refused with no rationale to show = the dialog was not shown at all.
        if (step != null && activity != null &&
            result.any { (perm, ok) -> !ok && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) }) {
            blocked = blocked + step.id
        }
    }
    // Keyed on the style: choosing another language stops the intro and says it again in that language.
    var introduced by remember { mutableStateOf(false) }
    LaunchedEffect(style) {
        if (introduced) c.speaker.stop()
        introduced = true
        c.speaker.say(s("Namaste! I am Saathi. Choose your language, allow what you are comfortable with, then tap Start.",
            "नमस्ते! मैं साथी हूँ। अपनी भाषा चुनिए, जो अनुमति ठीक लगे वो दीजिए, फिर शुरू करें दबाइए।",
            "Namaste! Main Saathi hoon. Apni bhasha chuniye, jo permission theek lage woh dijiye, phir Start dabaiye."), style)
    }

    SaathiScaffold(s("Welcome to Saathi", "साथी में आपका स्वागत है", "Saathi mein swagat hai"), onBack = onBack) {
        Text(s("Language", "भाषा", "Bhasha"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        // "Auto" is the stored default, so it is offered too: on first run one choice is always highlighted.
        listOf("auto" to s("Auto", "ऑटो", "Auto"), "en" to "English", "hi" to "हिन्दी", "hinglish" to "Hinglish").chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (code, label) ->
                    val chosen = settings.language == code
                    Surface(
                        onClick = { scope.launch { c.settings.setLanguage(code) } },
                        shape = RoundedCornerShape(18.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primary else Color.White,
                        contentColor = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface,
                        border = if (chosen) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics { role = Role.RadioButton; selected = chosen },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        Text(s("Auto replies in the same language you speak.", "ऑटो: आप जिस भाषा में बोलेंगे, उसी में जवाब।",
            "Auto: aap jis bhasha mein bolenge, usi mein jawab."), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(s("Allow what you are comfortable with", "जो ठीक लगे, उसकी अनुमति दीजिए", "Jo theek lage, uski permission dijiye"),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(s("You can skip any of these. Saathi will ask again only when a feature needs it.",
            "इनमें से कोई भी छोड़ सकते हैं। ज़रूरत पड़ने पर साथी फिर पूछेगी।",
            "Inme se koi bhi chhod sakte hain. Zaroorat padne par Saathi phir poochegi."),
            fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Permissions.steps.forEachIndexed { i, step ->
            val granted = remember(refresh) { step.granted(ctx) }
            val isBlocked = step.id in blocked && !granted
            PermissionRow(step.emoji, step.title(style), step.why(style), granted, s("Allow", "अनुमति दें", "Allow"),
                s("Allowed", "मिल गई", "Mil gayi"), Modifier.riseIn(i)) {
                when {
                    isBlocked -> Permissions.open(ctx, Permissions.appSettingsIntent(ctx))
                    step.runtime.isNotEmpty() -> { asking = step; launcher.launch(step.runtime.toTypedArray()) }
                    else -> step.settingsIntent?.let { Permissions.open(ctx, it(ctx)) }
                }
            }
            if (isBlocked) {
                val hint = s("Android won't ask again. Tap Allow to open App info, then Permissions, and turn it on there.",
                    "एंड्रॉइड अब दोबारा नहीं पूछेगा। अनुमति दें दबाइए, फिर ऐप जानकारी में Permissions खोलकर इसे चालू कीजिए।",
                    "Android ab dobara nahi poochega. Allow dabaiye, phir App info mein Permissions khol ke ise on kijiye.")
                LaunchedEffect(Unit) { c.speaker.say(hint, style) }
                Text(hint, fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
            if (step.id == "accessibility" && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton({ Permissions.open(ctx, Permissions.restrictedSettingsIntent(ctx)) }) {
                    Text(s("Switch is grey? Open App info → ⋮ → Allow restricted settings",
                        "बटन धुंधला है? ऐप जानकारी → ⋮ → Allow restricted settings",
                        "Button grey hai? App info → ⋮ → Allow restricted settings"), fontSize = 16.sp)
                }
            }
        }

        BigButton(s("Start using Saathi", "साथी शुरू करें", "Saathi shuru karein"), {
            scope.launch { c.settings.setSetupDone(); onFinished() }
        }, Modifier.padding(top = 8.dp))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** One permission: what it is for, and either an Allow button or a green "Allowed" tick. Tapping the row allows. */
@Composable
private fun PermissionRow(
    emoji: String, title: String, why: String, granted: Boolean, allow: String, allowed: String,
    modifier: Modifier, onAllow: () -> Unit,
) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = { if (!granted) onAllow() },
        interactionSource = press,
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.5.dp, if (granted) Leaf.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth().then(pressMotion).semantics(mergeDescendants = true) {
            contentDescription = "$title. $why. ${if (granted) allowed else allow}"
        },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(why, fontSize = 16.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Leaf50), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Leaf)
                }
            } else {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
                    Text(allow, style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp), textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}
