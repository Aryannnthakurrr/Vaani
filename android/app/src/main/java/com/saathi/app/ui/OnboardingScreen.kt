package com.saathi.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Edge
import com.saathi.app.ui.theme.Ink
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import kotlinx.coroutines.launch

/**
 * First-run setup (Stitch "3.3"): a welcome card that replays the spoken intro, the language, then every
 * permission as a card the user can allow or leave. "Start" stays fixed at the bottom and always works; anything
 * skipped is asked for later, when a feature needs it.
 */
@Composable
fun OnboardingScreen(c: AppContainer, onBack: (() -> Unit)? = null, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    fun a(en: String, hi: String) = alt(style, en, hi)
    val other = if (style == ReplyStyle.HI) ReplyStyle.EN else ReplyStyle.HI
    // Bumped on resume and after a permission dialog, so the ticks re-check what the user just allowed.
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
    val intro = s("Namaste! I am Saathi. Choose your language, allow what you are comfortable with, then tap Start.",
        "नमस्ते! मैं साथी हूँ। अपनी भाषा चुनिए, जो अनुमति ठीक लगे वो दीजिए, फिर शुरू करें दबाइए।",
        "Namaste! Main Saathi hoon. Apni bhasha chuniye, jo permission theek lage woh dijiye, phir Start dabaiye.")
    // Keyed on the style: choosing another language stops the intro and says it again in that language.
    var introduced by remember { mutableStateOf(false) }
    LaunchedEffect(style) {
        if (introduced) c.speaker.stop()
        introduced = true
        c.speaker.say(intro, style)
    }

    SaathiScaffold(
        s("Setup & language", "सेटअप और भाषा", "Setup aur bhasha"), onBack = onBack, subtitle = a("Setup & language", "भाषा और अनुमति"),
        bottomBar = {
            IconButtonBig(s("Start using Saathi", "साथी शुरू करें", "Saathi shuru karein"), Icons.Filled.RocketLaunch, {
                scope.launch { c.settings.setSetupDone(); onFinished() }
            }, second = a("Start using Saathi", "साथी शुरू करें"), height = 72.dp)
        },
    ) {
        // Welcome: tap to hear the spoken intro again.
        val (press, pressMotion) = rememberPress()
        Surface(onClick = { scope.launch { c.speaker.stop(); c.speaker.say(intro, style) } }, interactionSource = press,
            shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.5.dp, Edge),
            modifier = Modifier.fillMaxWidth().then(pressMotion)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBubble(Icons.Filled.VolumeUp, Color.White, MaterialTheme.colorScheme.primary, size = 52.dp, round = true)
                TwoLine(s("Welcome to Saathi", "साथी में आपका स्वागत है", "Saathi mein swagat hai"),
                    a("Welcome to Saathi", "साथी में आपका स्वागत है"), Modifier.weight(1f))
                IconBubble(Icons.Filled.Replay, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 44.dp, round = true)
            }
        }

        TwoLine(s("Choose language", "भाषा चुनें", "Bhasha chuniye") + " / " + a("Choose language", "भाषा चुनें"),
            s("Auto replies in the same language you speak.", "ऑटो: आप जिस भाषा में बोलेंगे, उसी में जवाब।",
                "Auto: aap jis bhasha mein bolenge, usi mein jawab."),
            Modifier.semantics { heading() }, mainStyle = MaterialTheme.typography.titleLarge)
        // "Auto" is the stored default, so it is offered too: on first run one choice is always highlighted.
        listOf(
            Triple("auto", s("Auto", "ऑटो", "Auto"), a("Detects by itself", "स्वतः पहचान")),
            Triple("en", "English", s("Simple clear words", "सरल अंग्रेज़ी", "Simple English")),
            Triple("hi", "हिन्दी", "सरल बोलचाल"),
            Triple("hinglish", "Hinglish", "Hindi + English mix"),
        ).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (code, label, sub) ->
                    LanguageChoice(label, sub, settings.language == code, Modifier.weight(1f)) { scope.launch { c.settings.setLanguage(code) } }
                }
            }
        }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Leaf50)
            .border(1.5.dp, Leaf.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBubble(Icons.Filled.GppGood, Leaf, Color.White, size = 44.dp, round = true)
            TwoLine(s("Allow what you are comfortable with. You can skip any of these safely.",
                "जो ठीक लगे, उसकी अनुमति दीजिए। कोई भी छोड़ सकते हैं।",
                "Jo theek lage, uski permission dijiye. Koi bhi chhod sakte hain."),
                s("Saathi will ask again only when a feature needs it.", "ज़रूरत पड़ने पर साथी फिर पूछेगी।",
                    "Zaroorat padne par Saathi phir poochegi."),
                Modifier.weight(1f), mainStyle = MaterialTheme.typography.bodyLarge, mainColor = Ink)
        }

        Permissions.steps.forEachIndexed { i, step ->
            val granted = remember(refresh) { step.granted(ctx) }
            val isBlocked = step.id in blocked && !granted
            PermissionCard(stepIcon(step.id), step.title(style), step.title(other).takeIf { it != step.title(style) },
                step.why(style), if (step.id == "sms_calls") s("Scam Shield", "धोखा-रक्षक", "Scam Shield") else null,
                granted, s("Allow", "अनुमति दें", "Allow"), s("Allowed", "मिल गई", "Mil gayi"), Modifier.riseIn(i)) {
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
                Text(hint, fontSize = 17.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.error)
            }
            if (step.id == "accessibility" && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The greyed-out switch on sideloaded builds: a tip card that opens App info.
                val (tipPress, tipMotion) = rememberPress()
                Surface(onClick = { Permissions.open(ctx, Permissions.restrictedSettingsIntent(ctx)) }, interactionSource = tipPress,
                    shape = RoundedCornerShape(16.dp), color = Amber50, border = BorderStroke(1.5.dp, Amber.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().then(tipMotion)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBubble(Icons.AutoMirrored.Filled.HelpOutline, Amber, Color.White, size = 44.dp, round = true)
                        Column(Modifier.weight(1f)) {
                            TwoLine(s("Switch is grey?", "बटन धुंधला है?", "Button grey hai?"), a("Switch is grey?", "बटन नहीं दब रहा?"))
                            Text(s("Open App info → ⋮ → Allow restricted settings", "ऐप जानकारी → ⋮ → Allow restricted settings",
                                "App info → ⋮ → Allow restricted settings"), fontSize = 17.sp, lineHeight = 23.sp, color = Ink)
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The icon for each permission, from one icon set (no emoji). */
private fun stepIcon(id: String): ImageVector = when (id) {
    "mic" -> Icons.Filled.Mic
    "notify" -> Icons.Filled.Notifications
    "sms_calls" -> Icons.AutoMirrored.Filled.Chat
    "camera_cal" -> Icons.Filled.PhotoCamera
    "location" -> Icons.Filled.LocationOn
    "accessibility" -> Icons.Filled.TouchApp
    "notif_listener" -> Icons.Filled.MarkEmailRead
    "overlay" -> Icons.Filled.PictureInPicture
    "battery" -> Icons.Filled.BatteryChargingFull
    "alarms" -> Icons.Filled.Alarm
    else -> Icons.Filled.Settings
}

/** A language option: filled teal with a tick when chosen, white and outlined otherwise, with a short description. */
@Composable
private fun LanguageChoice(label: String, sub: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = onClick, interactionSource = press, shape = RoundedCornerShape(16.dp),
        color = if (chosen) MaterialTheme.colorScheme.primary else Color.White,
        contentColor = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface,
        border = if (chosen) null else BorderStroke(1.5.dp, Edge),
        modifier = modifier.heightIn(min = 72.dp).then(pressMotion).semantics { role = Role.RadioButton; selected = chosen },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                if (chosen) Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.padding(start = 6.dp).size(22.dp))
            }
            Text(sub, fontSize = 15.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                color = if (chosen) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One permission as a card: icon, the name with its second-language line, an optional tag ("Scam Shield"), why it
 * is needed, then Allow (teal) or a green "Allowed". Tapping anywhere on the card allows.
 */
@Composable
private fun PermissionCard(
    icon: ImageVector, title: String, second: String?, why: String, tag: String?, granted: Boolean, allow: String, allowed: String,
    modifier: Modifier, onAllow: () -> Unit,
) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = { if (!granted) onAllow() },
        interactionSource = press,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.5.dp, if (granted) Leaf.copy(alpha = 0.5f) else Edge),
        modifier = modifier.fillMaxWidth().then(pressMotion).semantics(mergeDescendants = true) {
            contentDescription = "$title. $why. ${if (granted) allowed else allow}"
        },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBubble(icon, if (granted) Leaf else MaterialTheme.colorScheme.primary,
                    if (granted) Leaf50 else MaterialTheme.colorScheme.primaryContainer, size = 48.dp, round = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title + (second?.let { " / $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
                    tag?.let { StatusPill(it, Leaf, filled = false) }
                    Text(why, fontSize = 18.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                if (granted) {
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Leaf50).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Leaf)
                        Text(allowed, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Leaf)
                    }
                } else {
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary)
                        .heightIn(min = 52.dp).padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Key, null, tint = Color.White)
                        Text(allow, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}
