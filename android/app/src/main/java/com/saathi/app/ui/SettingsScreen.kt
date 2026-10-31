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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
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
import com.saathi.app.core.net.Health
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.overlay.AlertLevel
import com.saathi.app.core.overlay.ScamAlert
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.util.Packages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onSetup: () -> Unit) {
    SecureWindow() // private data: no screenshots or screen recording
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState()
    val style = s.replyStyle
    // Null while checking; the result is kept (not a sentence) so it follows a language change.
    var health by remember { mutableStateOf<Result<Health>?>(null) }
    // A save that reached the phone but not the server says so, instead of crashing the screen.
    fun synced(ok: Boolean) = if (ok) tr(style, "Saved.", "सेव हो गया।", "Save ho gaya.")
        else tr(style, "Saved on this phone, but the Saathi server could not be reached. Your family's alerts will update when it is back.",
            "फ़ोन में सेव हो गया, पर साथी सर्वर से जुड़ नहीं पाए। सर्वर मिलते ही परिवार वाले अलर्ट अपडेट होंगे।",
            "Phone mein save ho gaya, par Saathi server se connect nahi ho paya. Server milte hi family alerts update honge.")
    val saving = tr(style, "Saving…", "सेव हो रहा है…", "Save ho raha hai…")

    LaunchedEffect(s.backendUrl, s.useFakeBackend) {
        health = null
        health = runCatching { c.backend.health() }.onSuccess { c.assistant.speech.cloudSpeech = it.sarvam }
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
        listOf("auto" to tr(style, "Auto", "ऑटो", "Auto"), "en" to "English", "hi" to "हिन्दी", "hinglish" to "Hinglish").chunked(2).forEach { pair ->
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
        val nameNote = rememberNote()
        BigField(tr(style, "Your name", "आपका नाम", "Aapka naam"), name) { name = it }
        BigButton(if (nameNote.busy) saving else tr(style, "Save name", "नाम सेव करें", "Naam save karein"), {
            nameNote.run(scope) { c.settings.setUserName(name.trim()); c.welfare.pushConfig().let { synced(it) to it } }
        }, enabled = !nameNote.busy)
        NoteLine(nameNote)

        }
        SettingsCard("👨‍👩‍👧", tr(style, "Family (Telegram)", "परिवार (टेलीग्राम)", "Family (Telegram)")) {
        InfoCard(
            title = tr(style, "How family joins", "परिवार कैसे जुड़े", "Family kaise jude"),
            body = tr(style, "Your family member opens the Saathi bot in Telegram and sends /start. The bot replies with a chat ID. Type it below.",
                "परिवार का सदस्य टेलीग्राम में साथी बॉट खोलकर /start भेजे। बॉट एक चैट आईडी भेजेगा। उसे नीचे लिखिए।",
                "Family member Telegram mein Saathi bot khol ke /start bheje. Bot ek chat ID bhejega. Use neeche likhiye."),
        )
        val familyNote = rememberNote()
        var removing by remember { mutableStateOf<FamilyContact?>(null) }
        s.contacts.forEach { contact ->
            InfoCard(title = contact.name, body = tr(style, "Chat ID", "चैट आईडी", "Chat ID") + " " + contact.telegramChatId) {
                BigButton(tr(style, "Remove", "हटाएँ", "Hatayein"), { removing = contact }, color = Danger, enabled = !familyNote.busy)
            }
        }
        // Removing someone stops their alerts, so it is asked once more.
        removing?.let { contact ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(tr(style, "Remove ${contact.name}?", "${contact.name} को हटाएँ?", "${contact.name} ko hatayein?")) },
                text = { Text(tr(style, "They will stop getting your alerts on Telegram.", "उन्हें टेलीग्राम पर आपके अलर्ट मिलने बंद हो जाएँगे।",
                    "Unhe Telegram par aapke alerts milna band ho jayega."), style = MaterialTheme.typography.bodyLarge) },
                confirmButton = {
                    TextButton({
                        removing = null
                        familyNote.run(scope) { c.settings.setContacts(c.settings.fresh().contacts - contact); c.welfare.pushConfig().let { synced(it) to it } }
                    }) { Text(tr(style, "Remove", "हटाएँ", "Hatayein"), color = Danger, style = MaterialTheme.typography.titleMedium) }
                },
                dismissButton = {
                    TextButton({ removing = null }) { Text(tr(style, "Keep", "रहने दें", "Rehne dein"), style = MaterialTheme.typography.titleMedium) }
                },
            )
        }
        var cName by remember { mutableStateOf("") }
        var cId by remember { mutableStateOf("") }
        BigField(tr(style, "Family member's name", "परिवार के सदस्य का नाम", "Family member ka naam"), cName) { cName = it }
        BigField(tr(style, "Telegram chat ID", "टेलीग्राम चैट आईडी", "Telegram chat ID"), cId) { cId = it.filter { ch -> ch.isDigit() || ch == '-' } }
        BigButton(if (familyNote.busy) saving else tr(style, "Add family member", "सदस्य जोड़ें", "Member jodein"), {
            if (cName.isNotBlank() && cId.isNotBlank()) familyNote.run(scope) {
                c.settings.setContacts(c.settings.fresh().contacts + FamilyContact(cName.trim(), cId.trim()))
                cName = ""; cId = ""
                c.welfare.pushConfig().let { synced(it) to it }
            } else familyNote.show(tr(style, "Please write the name and the chat ID.", "कृपया नाम और चैट आईडी लिखिए।",
                "Please naam aur chat ID likhiye."), ok = false)
        }, enabled = !familyNote.busy)
        NoteLine(familyNote)

        }
        SettingsCard("💛", tr(style, "Daily check-in", "रोज़ की जाँच", "Daily check-in")) {
        var check by remember(s.welfareCheckTime) { mutableStateOf(s.welfareCheckTime) }
        var prompt by remember(s.welfarePromptTime) { mutableStateOf(s.welfarePromptTime) }
        BigField(tr(style, "Ask me at (HH:MM)", "मुझसे पूछें (HH:MM)", "Mujhse poochein (HH:MM)"), prompt) { prompt = it }
        BigField(tr(style, "Alert family at (HH:MM)", "परिवार को बताएँ (HH:MM)", "Family ko batayein (HH:MM)"), check) { check = it }
        val checkNote = rememberNote()
        var testing by remember { mutableStateOf(false) }
        BigButton(if (checkNote.busy) saving else tr(style, "Save times", "समय सेव करें", "Time save karein"), {
            val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (ok.matches(check) && ok.matches(prompt)) checkNote.run(scope) {
                c.settings.setWelfareTimes(check, prompt)
                c.welfare.scheduleDailyPrompt(prompt)
                c.welfare.pushConfig().let { synced(it) to it }
            } else checkNote.show(tr(style, "Please write the time like 11:30", "समय ऐसे लिखिए: 11:30", "Time aise likhiye: 11:30"), ok = false)
        }, enabled = !checkNote.busy)
        // One test at a time: every tap messages the family.
        BigButton(if (testing) tr(style, "Sending…", "भेज रहे हैं…", "Bhej rahe hain…") else tr(style, "Test welfare alert", "जाँच संदेश भेजकर देखें", "Test welfare alert"), {
            if (!testing) scope.launch {
                testing = true
                try { checkNote.show(c.welfare.sendTestAlert(), ok = null, millis = 10_000) } finally { testing = false }
            }
        }, color = MaterialTheme.colorScheme.secondary, enabled = !testing)
        NoteLine(checkNote)
        ToggleRow(tr(style, "Tell family about scam alerts", "धोखे की चेतावनी परिवार को भी भेजें", "Scam alert family ko bhi bhejein"), s.notifyFamilyOnScam) {
            scope.launch { c.settings.setNotifyFamilyOnScam(it) }
        }

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
        health?.let { InfoCard(title = healthLine(it, style), accent = if (it.isSuccess) Leaf else Danger) }
        var url by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
        val urlNote = rememberNote()
        BigField(tr(style, "Server address", "सर्वर का पता", "Server ka address"), url) { url = it }
        BigButton(tr(style, "Save address", "पता सेव करें", "Address save karein"), {
            urlNote.run(scope) { c.settings.setBackendUrl(url.trim()); tr(style, "Saved.", "सेव हो गया।", "Save ho gaya.") to true }
        }, enabled = !urlNote.busy)
        NoteLine(urlNote)
        ToggleRow(tr(style, "Work offline (no backend)", "बिना सर्वर के चलाएँ", "Bina server ke chalayein"), s.useFakeBackend) {
            scope.launch { c.settings.setUseFakeBackend(it) }
        }
        BigButton(tr(style, "Run setup again", "सेटअप फिर से", "Setup phir se"), onSetup,
            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)

        }

        if (BuildConfig.DEBUG) DebugSection(c, style, s.replayMode)
    }
}

/** The Connection card's status line, in the current language. */
private fun healthLine(r: Result<Health>, style: ReplyStyle): String = r.fold(
    { h ->
        "✅ " + listOf(
            if (h.mock) tr(style, "Practice mode (no AI keys)", "अभ्यास मोड (AI चाबी नहीं)", "Practice mode (AI keys nahi)")
            else tr(style, "Live AI", "AI चालू", "Live AI"),
            tr(style, "voice: ", "आवाज़: ", "awaaz: ") + if (h.sarvam) "Sarvam" else tr(style, "on the phone", "फ़ोन पर", "phone par"),
            "Telegram: " + if (h.telegram) tr(style, "on", "चालू", "on") else tr(style, "log only", "सिर्फ़ लॉग", "sirf log"),
        ).joinToString(" · ")
    },
    { tr(style, "⚠️ Server not reachable. Saathi is using the phone's own voice and checks.",
        "⚠️ सर्वर से नहीं जुड़ पाए। साथी फ़ोन की अपनी आवाज़ और जाँच से काम कर रहा है।",
        "⚠️ Server se connect nahi ho paya. Saathi phone ki apni awaaz aur checks se kaam kar raha hai.") },
)

/**
 * A short result line under one card's buttons ("Saved.", or what went wrong). It clears itself after a few
 * seconds, so an old message never sits under a card the user has changed since.
 */
@Stable
private class Note {
    var text by mutableStateOf<String?>(null)
        private set
    /** True: green, false: red, null: neutral. */
    var ok by mutableStateOf<Boolean?>(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var millis = 4_000L
        private set
    /** Bumped on every message, so the same text shown twice still gets its full few seconds. */
    var seq by mutableIntStateOf(0)
        private set

    fun show(text: String, ok: Boolean?, millis: Long = 4_000L) {
        this.text = text; this.ok = ok; this.millis = millis; seq++
    }

    fun clear() { text = null }

    /** Runs one save at a time; [block] returns the message and whether everything worked. */
    fun run(scope: CoroutineScope, block: suspend () -> Pair<String, Boolean>) {
        if (busy) return
        busy = true
        scope.launch {
            try { block().let { (msg, ok) -> show(msg, ok) } } finally { busy = false }
        }
    }
}

@Composable
private fun rememberNote(): Note {
    val note = remember { Note() }
    LaunchedEffect(note.seq) {
        if (note.text != null) { delay(note.millis); note.clear() }
    }
    return note
}

@Composable
private fun NoteLine(note: Note) {
    note.text?.let { InfoCard(title = it, accent = when (note.ok) { true -> Leaf; false -> Danger; null -> null }) }
}

@Composable
private fun DebugSection(c: AppContainer, style: ReplyStyle, replay: Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    val report: (String) -> Unit = { status = it }
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
    if (status.isNotBlank()) InfoCard(title = status)
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
