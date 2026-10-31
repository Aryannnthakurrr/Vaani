package com.saathi.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.saathi.app.BuildConfig
import com.saathi.app.core.AppContainer
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.agent.LiveScreenReader
import com.saathi.app.core.agent.ScreenRecorder
import com.saathi.app.core.net.FamilyContact
import com.saathi.app.core.overlay.AlertLevel
import com.saathi.app.core.overlay.ScamAlert
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.util.Packages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState()
    val style = s.replyStyle
    var status by remember { mutableStateOf("") }
    var health by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(s.backendUrl, s.useFakeBackend) {
        health = runCatching { c.backend.health() }.fold(
            { h ->
                c.assistant.speech.cloudSpeech = h.sarvam
                "✅ ${if (h.mock) "Mock mode (no AI keys)" else "Live AI"} · speech: ${if (h.sarvam) "Sarvam" else "on-device"} · Telegram: ${if (h.telegram) "on" else "log only"}"
            },
            { "⚠️ Backend not reachable — using on-device fallbacks" },
        )
    }

    SaathiScaffold(tr(style, "Settings", "सेटिंग्स", "Settings"), onBack) {
        if (c.profiles.all.isNotEmpty()) SettingsCard("🧑‍🦳", tr(style, "Demo profile", "डेमो प्रोफ़ाइल", "Demo profile")) {
            var switching by remember { mutableStateOf(false) }
            Text(tr(style, "Switch to a demo user: their health, preferences, messages and next appointment.",
                "डेमो उपयोगकर्ता चुनें: उनकी सेहत, पसंद, संदेश और अगला अपॉइंटमेंट।",
                "Demo user chunein: unki health, pasand, messages aur agla appointment."), style = MaterialTheme.typography.bodyMedium)
            c.profiles.all.forEach { p ->
                val active = s.profileId == p.id
                BigButton("${if (active) "✓ " else ""}${p.name}, ${p.age} · ${p.conditions.joinToString(", ")}",
                    { if (!switching) scope.launch { switching = true; c.profiles.switchTo(p); switching = false } },
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = !switching)
            }
            if (s.profileId.isNotEmpty()) {
                BigButton(tr(style, "Back to my own profile", "अपनी प्रोफ़ाइल पर वापस", "Apni profile par wapas"),
                    { scope.launch { c.profiles.clear() } })
            }
        }
        SettingsCard("🗣️", tr(style, "Language", "भाषा", "Bhasha")) {
        listOf("auto" to "Auto", "en" to "English", "hi" to "हिन्दी", "hinglish" to "Hinglish").chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (code, label) ->
                    BigButton(label, { scope.launch { c.settings.setLanguage(code) } }, Modifier.weight(1f),
                        color = if (s.language == code) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (s.language == code) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(tr(style, "Auto replies in the same language you speak.", "ऑटो: आप जिस भाषा में बोलेंगे, उसी में जवाब।",
            "Auto: aap jis bhasha mein bolenge, usi mein jawab."), style = MaterialTheme.typography.bodyMedium)

        var name by remember(s.userName) { mutableStateOf(s.userName) }
        BigField(tr(style, "Your name", "आपका नाम", "Aapka naam"), name) { name = it }
        BigButton(tr(style, "Save name", "नाम सेव करें", "Naam save karein"), { scope.launch { c.settings.setUserName(name.trim()); c.welfare.pushConfig() } })

        }
        SettingsCard("👨‍👩‍👧", tr(style, "Family (Telegram)", "परिवार (टेलीग्राम)", "Family (Telegram)")) {
        InfoCard(
            title = tr(style, "How family joins", "परिवार कैसे जुड़े", "Family kaise jude"),
            body = tr(style, "Your family member opens the Saathi bot in Telegram and sends /start. The bot replies with a chat ID. Type it below.",
                "परिवार का सदस्य टेलीग्राम में साथी बॉट खोलकर /start भेजे। बॉट एक चैट आईडी भेजेगा। उसे नीचे लिखिए।",
                "Family member Telegram mein Saathi bot khol ke /start bheje. Bot ek chat ID bhejega. Use neeche likhiye."),
        )
        s.contacts.forEach { contact ->
            InfoCard(title = contact.name, body = "Chat ID ${contact.telegramChatId}") {
                BigButton(tr(style, "Remove", "हटाएँ", "Hatayein"), {
                    scope.launch { c.settings.setContacts(s.contacts - contact); c.welfare.pushConfig() }
                }, color = Danger)
            }
        }
        var cName by remember { mutableStateOf("") }
        var cId by remember { mutableStateOf("") }
        BigField(tr(style, "Family member's name", "परिवार के सदस्य का नाम", "Family member ka naam"), cName) { cName = it }
        BigField(tr(style, "Telegram chat ID", "टेलीग्राम चैट आईडी", "Telegram chat ID"), cId) { cId = it.filter { ch -> ch.isDigit() || ch == '-' } }
        BigButton(tr(style, "Add family member", "सदस्य जोड़ें", "Member jodein"), {
            if (cName.isNotBlank() && cId.isNotBlank()) scope.launch {
                c.settings.setContacts(s.contacts + FamilyContact(cName.trim(), cId.trim()))
                c.welfare.pushConfig()
                cName = ""; cId = ""
            }
        })

        }
        SettingsCard("💛", tr(style, "Daily check-in", "रोज़ की जाँच", "Daily check-in")) {
        var check by remember(s.welfareCheckTime) { mutableStateOf(s.welfareCheckTime) }
        var prompt by remember(s.welfarePromptTime) { mutableStateOf(s.welfarePromptTime) }
        BigField(tr(style, "Ask me at (HH:MM)", "मुझसे पूछें (HH:MM)", "Mujhse poochein (HH:MM)"), prompt) { prompt = it }
        BigField(tr(style, "Alert family at (HH:MM)", "परिवार को बताएँ (HH:MM)", "Family ko batayein (HH:MM)"), check) { check = it }
        BigButton(tr(style, "Save times", "समय सेव करें", "Time save karein"), {
            val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (ok.matches(check) && ok.matches(prompt)) scope.launch {
                c.settings.setWelfareTimes(check, prompt)
                c.welfare.scheduleDailyPrompt()
                c.welfare.pushConfig()
                status = tr(style, "Saved.", "सेव हो गया।", "Save ho gaya.")
            } else status = tr(style, "Please write the time like 11:30", "समय ऐसे लिखिए: 11:30", "Time aise likhiye: 11:30")
        })
        BigButton(tr(style, "Test welfare alert", "जाँच संदेश भेजकर देखें", "Test welfare alert"), {
            scope.launch { status = c.welfare.sendTestAlert() }
        }, color = MaterialTheme.colorScheme.secondary)
        ToggleRow(tr(style, "Tell family about scam alerts", "धोखे की चेतावनी परिवार को भी भेजें", "Scam alert family ko bhi bhejein"), s.notifyFamilyOnScam) {
            scope.launch { c.settings.setNotifyFamilyOnScam(it) }
        }
        if (status.isNotBlank()) InfoCard(title = status)

        }
        SettingsCard("📱", tr(style, "Apps Saathi can use", "ऐप्स जिनमें साथी काम कर सकता है", "Apps jinmein Saathi kaam kar sakta hai")) {
        Packages.ACT_ALLOWLIST.filter { it != Packages.SELF }.forEach { pkg ->
            val installed = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrElse { it !is PackageManager.NameNotFoundException }
            Text(
                (if (installed) "✅  " else "⬜  ") + Packages.displayName(pkg) + " · " +
                    if (installed) tr(style, "installed", "इंस्टॉल है", "installed hai") else tr(style, "not installed", "इंस्टॉल नहीं", "installed nahi"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        }
        SettingsCard("🔌", tr(style, "Connection", "कनेक्शन", "Connection")) {
        health?.let { InfoCard(title = it, accent = if (it.startsWith("✅")) Leaf else Danger) }
        var url by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
        BigField("Backend URL", url) { url = it }
        BigButton(tr(style, "Save URL", "URL सेव करें", "URL save karein"), { scope.launch { c.settings.setBackendUrl(url.trim()) } })
        ToggleRow(tr(style, "Work offline (no backend)", "बिना सर्वर के चलाएँ", "Bina server ke chalayein"), s.useFakeBackend) {
            scope.launch { c.settings.setUseFakeBackend(it) }
        }
        BigButton(tr(style, "Run setup again", "सेटअप फिर से", "Setup phir se"), onSetup,
            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)

        }

        if (BuildConfig.DEBUG) DebugSection(c, style, s.replayMode) { status = it }
    }
}

@Composable
private fun DebugSection(c: AppContainer, style: com.saathi.app.core.net.ReplyStyle, replay: Boolean, report: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    SettingsCard("🛠️", "Developer") {
    ToggleRow("Demo replay: run tasks on recorded screens", replay) { scope.launch { c.settings.setReplayMode(it) } }
    BigButton("Record screen in 5 s", {
        scope.launch {
            report("Switch to the app now…")
            delay(5_000)
            val path = if (AgentService.instance == null) null else ScreenRecorder.record(ctx, LiveScreenReader { AgentService.instance })
            report(path?.let { "Saved $it" } ?: "Could not record (is accessibility on?)")
        }
    })
    BigButton("Show a test scam alert", {
        c.alerts.show(ScamAlert(AlertLevel.RED, tr(style, "Scam warning", "धोखे की चेतावनी", "Scam warning"),
            c.phrases.text("scam_red_ussd", style), "scam_red_ussd"))
        scope.launch { c.speaker.sayPhrase("scam_red_ussd", style) }
    }, color = Danger)
    // Data used since the app started, per feature (app side; the server's view is GET /usage).
    var tick by remember { mutableStateOf(0) }
    val usage = remember(tick) { com.saathi.app.core.net.UsageMeter.snapshot() }
    val total = remember(tick) { com.saathi.app.core.net.UsageMeter.total() }
    fun kb(b: Long) = "%.1f KB".format(b / 1024.0)
    Text("Data used: ${kb(total.bytesUp)} up · ${kb(total.bytesDown)} down · ${total.requests} requests",
        style = MaterialTheme.typography.titleMedium)
    usage.forEach { (k, v) -> Text("$k: ${kb(v.bytesUp)} up · ${kb(v.bytesDown)} down · ${v.requests} req", style = MaterialTheme.typography.bodyMedium) }
    BigButton("Refresh data used", { tick++ })
    BigButton("Reset data counter", { com.saathi.app.core.net.UsageMeter.reset(); tick++ })
    }
}

/** One settings group: a card with an icon and a heading. */
@Composable
private fun SettingsCard(icon: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(icon, fontSize = 30.sp)
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
private fun BigField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
