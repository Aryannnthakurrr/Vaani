package com.saathi.app.ui

import com.saathi.app.ui.theme.Touch
import com.saathi.app.ui.theme.Space
import com.saathi.app.ui.theme.Border
import com.saathi.app.ui.theme.OnFill
import com.saathi.app.ui.theme.Paper
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.BuildConfig
import com.saathi.app.core.AppContainer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import com.saathi.app.ui.theme.Danger50
import com.saathi.app.ui.theme.Edge
import com.saathi.app.ui.theme.Ink
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50
import com.saathi.app.ui.theme.Muted
import com.saathi.app.ui.theme.Sky
import com.saathi.app.ui.theme.Sky50
import com.saathi.app.ui.theme.Soft
import com.saathi.app.util.Packages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings (Stitch "3.2"): one outlined card per group (language, name, family on Telegram, daily check-in, scam
 * alerts to family, apps, connection), every heading with its second-language line, then "Run setup again".
 */
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onSetup: () -> Unit) {
    SecureWindow() // private data: no screenshots or screen recording
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState()
    val style = s.replyStyle
    fun t(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    fun a(en: String, hi: String) = alt(style, en, hi)
    // Null while checking; the result is kept (not a sentence) so it follows a language change.
    var health by remember { mutableStateOf<Result<Health>?>(null) }
    // A save that reached the phone but not the server says so, instead of crashing the screen.
    fun synced(ok: Boolean) = if (ok) t("Saved.", "सेव हो गया।", "Save ho gaya.")
        else t("Saved on this phone, but the Vaani server could not be reached. Your family's alerts will update when it is back.",
            "फ़ोन में सेव हो गया, पर वाणी सर्वर से जुड़ नहीं पाए। सर्वर मिलते ही परिवार वाले अलर्ट अपडेट होंगे।",
            "Phone mein save ho gaya, par Vaani server se connect nahi ho paya. Server milte hi family alerts update honge.")
    val saving = t("Saving…", "सेव हो रहा है…", "Save ho raha hai…")

    LaunchedEffect(s.backendUrl, s.useFakeBackend) {
        health = null
        health = runCatching { c.backend.health() }.onSuccess { c.assistant.speech.cloudSpeech = it.sarvam }
    }

    SaathiScaffold(t("Settings", "सेटिंग्स", "Settings"), onBack, subtitle = a("Settings", "आपकी सेटिंग्स")) {
        // Intro line and whether Saathi is connected, at a glance.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Text(t("Language, family and daily check-ins", "भाषा, परिवार और रोज़ की जाँच", "Bhasha, family aur daily check-in"),
                style = MaterialTheme.typography.bodyLarge, color = Muted, modifier = Modifier.weight(1f))
            when (health?.isSuccess) {
                true -> StatusPill(t("Connected", "जुड़ा है", "Connected"), Leaf, filled = false, dot = true)
                false -> StatusPill(t("Offline", "ऑफ़लाइन", "Offline"), Danger, filled = false, dot = true)
                null -> Unit
            }
        }

        if (c.profiles.all.isNotEmpty()) SectionCard(Icons.Filled.Person, t("Demo profile", "डेमो प्रोफ़ाइल", "Demo profile"),
            t("Switch to a demo user: their health, preferences, messages and next appointment.",
                "डेमो उपयोगकर्ता चुनें: उनकी सेहत, पसंद, संदेश और अगला अपॉइंटमेंट।",
                "Demo user chunein: unki health, pasand, messages aur agla appointment.")) {
            var switching by remember { mutableStateOf(false) }
            c.profiles.all.forEach { p ->
                val active = s.profileId == p.id
                Choice("${p.name}, ${p.age}", p.conditions.joinToString(", "), active, Modifier.fillMaxWidth()) {
                    if (!switching) scope.launch { switching = true; c.profiles.switchTo(p); switching = false }
                }
            }
            if (s.profileId.isNotEmpty()) {
                IconButtonBig(t("Back to my own profile", "अपनी प्रोफ़ाइल पर वापस", "Apni profile par wapas"), Icons.Filled.Person,
                    { scope.launch { c.profiles.clear() } }, color = Paper, contentColor = Ink, border = Edge, height = 56.dp)
            }
        }

        SectionCard(Icons.Filled.Translate, t("Language", "भाषा चुनें", "Bhasha chunein") + " (" + a("Language", "भाषा") + ")",
            t("Choose the language for voice and screen", "आवाज़ और स्क्रीन की भाषा चुनें", "Awaaz aur screen ki bhasha chuniye")) {
            listOf(
                Triple("auto", t("Auto", "ऑटो", "Auto"), a("Auto", "अपने आप")),
                Triple("en", "English", null),
                Triple("hi", "हिन्दी", "Hindi"),
                Triple("hinglish", "Hinglish", null),
            ).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    pair.forEach { (code, label, sub) ->
                        Choice(label, sub, s.language == code, Modifier.weight(1f)) { scope.launch { c.settings.setLanguage(code) } }
                    }
                }
            }
            InfoBox(Icons.Filled.Info, t("Auto replies in the same language you speak.", "ऑटो: आप जिस भाषा में बोलेंगे, उसी में जवाब।",
                "Auto: aap jis bhasha mein bolenge, usi mein jawab."),
                a("Auto replies in the same language you speak.", "जिस भाषा में बोलेंगे, उसी में उत्तर मिलेगा।"), Muted, Soft)
        }

        SectionCard(Icons.Filled.Badge, t("Your name", "आपका नाम", "Aapka naam") + " (" + a("Your name", "आपका नाम") + ")",
            t("How Vaani addresses you", "वाणी आपको किस नाम से बुलाए", "Vaani aapko kis naam se bulaye")) {
            var name by remember(s.userName) { mutableStateOf(s.userName) }
            val nameNote = rememberNote()
            BigField(null, null, name, placeholder = t("e.g. Ramesh Sharma", "जैसे रमेश शर्मा", "jaise Ramesh Sharma")) { name = it }
            IconButtonBig(if (nameNote.busy) saving else t("Save name", "नाम सेव करें", "Naam save karein"), Icons.Filled.Check, {
                nameNote.run(scope) { c.settings.setUserName(name.trim()); c.welfare.pushConfig().let { synced(it) to it } }
            }, second = if (nameNote.busy) null else a("Save name", "नाम सहेजें"), enabled = !nameNote.busy)
            NoteLine(nameNote)
        }

        SectionCard(Icons.Filled.Groups, t("Family (Telegram)", "परिवार (टेलीग्राम)", "Parivaar (Telegram)"),
            t("Family get your alerts and daily updates", "परिवार को आपके अलर्ट और रोज़ की जानकारी मिलती है",
                "Family ko aapke alerts aur daily updates milte hain")) {
            InfoBox(Icons.AutoMirrored.Filled.Send,
                t("How family joins", "परिवार कैसे जुड़े", "Family kaise jude") + ": " +
                    t("Your family member opens the Vaani bot in Telegram and sends /start. The bot replies with a chat ID. Type it below.",
                        "परिवार का सदस्य टेलीग्राम में वाणी बॉट खोलकर /start भेजे। बॉट एक चैट आईडी भेजेगा। उसे नीचे लिखिए।",
                        "Family member Telegram mein Vaani bot khol ke /start bheje. Bot ek chat ID bhejega. Use neeche likhiye."),
                null, Sky, Sky50)

            val familyNote = rememberNote()
            var removing by remember { mutableStateOf<FamilyContact?>(null) }
            if (s.contacts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Linked family", "जुड़े सदस्य", "Jude sadasya").uppercase(), style = MaterialTheme.typography.labelSmall,
                        color = Muted, modifier = Modifier.weight(1f))
                    StatusPill(t("${s.contacts.size} connected", "${s.contacts.size} जुड़े", "${s.contacts.size} connected"), Leaf, filled = false)
                }
            }
            s.contacts.forEach { contact ->
                Surface(shape = MaterialTheme.shapes.medium, color = Paper, border = BorderStroke(Border.control, Edge)) {
                    Row(Modifier.fillMaxWidth().padding(Space.lg), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Leaf50), contentAlignment = Alignment.Center) {
                            Text(initials(contact.name), fontWeight = FontWeight.Bold, color = Leaf, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(t("Chat ID", "चैट आईडी", "Chat ID") + ": " + contact.telegramChatId, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Surface(onClick = { removing = contact }, enabled = !familyNote.busy, shape = MaterialTheme.shapes.medium,
                            color = Danger50, contentColor = Danger, modifier = Modifier.heightIn(min = 52.dp)) {
                            Row(Modifier.padding(horizontal = Space.md, vertical = Space.md), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(22.dp))
                                Text(t("Remove", "हटाएँ", "Hatayein"), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            // Removing someone stops their alerts, so it is asked once more.
            removing?.let { contact ->
                AlertDialog(
                    onDismissRequest = { removing = null },
                    title = { Text(t("Remove ${contact.name}?", "${contact.name} को हटाएँ?", "${contact.name} ko hatayein?")) },
                    text = { Text(t("They will stop getting your alerts on Telegram.", "उन्हें टेलीग्राम पर आपके अलर्ट मिलने बंद हो जाएँगे।",
                        "Unhe Telegram par aapke alerts milna band ho jayega."), style = MaterialTheme.typography.bodyLarge) },
                    confirmButton = {
                        TextButton({
                            removing = null
                            familyNote.run(scope) { c.settings.setContacts(c.settings.fresh().contacts - contact); c.welfare.pushConfig().let { synced(it) to it } }
                        }) { Text(t("Remove", "हटाएँ", "Hatayein"), color = Danger, style = MaterialTheme.typography.titleMedium) }
                    },
                    dismissButton = {
                        TextButton({ removing = null }) { Text(t("Keep", "रहने दें", "Rehne dein"), style = MaterialTheme.typography.titleMedium) }
                    },
                )
            }
            // Adding someone: its own soft panel inside the card.
            Surface(shape = MaterialTheme.shapes.medium, color = Soft) {
                Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                        Text(t("Add a family member", "नया सदस्य जोड़ें", "Naya sadasya jodein"), style = MaterialTheme.typography.titleMedium)
                    }
                    var cName by remember { mutableStateOf("") }
                    var cId by remember { mutableStateOf("") }
                    BigField(t("Family member's name", "परिवार के सदस्य का नाम", "Family member ka naam"), null, cName,
                        placeholder = t("e.g. Priya Sharma", "जैसे प्रिया शर्मा", "jaise Priya Sharma")) { cName = it }
                    BigField(t("Telegram chat ID", "टेलीग्राम चैट आईडी", "Telegram chat ID"), null, cId, placeholder = "738291039") {
                        cId = it.filter { ch -> ch.isDigit() || ch == '-' }
                    }
                    IconButtonBig(if (familyNote.busy) saving else t("Add family member", "सदस्य जोड़ें", "Member jodein"), Icons.Filled.PersonAdd, {
                        if (cName.isNotBlank() && cId.isNotBlank()) familyNote.run(scope) {
                            c.settings.setContacts(c.settings.fresh().contacts + FamilyContact(cName.trim(), cId.trim()))
                            cName = ""; cId = ""
                            c.welfare.pushConfig().let { synced(it) to it }
                        } else familyNote.show(t("Please write the name and the chat ID.", "कृपया नाम और चैट आईडी लिखिए।",
                            "Please naam aur chat ID likhiye."), ok = false)
                    }, second = if (familyNote.busy) null else a("Add family member", "सदस्य जोड़ें"), enabled = !familyNote.busy)
                }
            }
            NoteLine(familyNote)
        }

        SectionCard(Icons.Filled.Schedule, t("Daily check-in", "रोज़ की जाँच", "Rozana haal-chaal"),
            t("A morning check-in. Family is told if you don't reply.", "सुबह हाल-चाल पूछना। जवाब न मिले तो परिवार को बताना।",
                "Subah haal-chaal. Jawab na mile to family ko batana.")) {
            var check by remember(s.welfareCheckTime) { mutableStateOf(s.welfareCheckTime) }
            var prompt by remember(s.welfarePromptTime) { mutableStateOf(s.welfarePromptTime) }
            BigField(t("Ask me at", "मुझसे पूछें", "Pehle mujhse poochein") + " (HH:MM)", Icons.Filled.Alarm, prompt, trailing = Icons.Filled.Schedule) { prompt = it }
            BigField(t("Alert family if I don't reply, at", "जवाब न दूँ तो परिवार को बताएँ", "Jawab na doon to family ko batayein") + " (HH:MM)",
                Icons.Filled.NotificationsActive, check, trailing = Icons.Filled.Schedule) { check = it }
            val checkNote = rememberNote()
            var testing by remember { mutableStateOf(false) }
            IconButtonBig(if (checkNote.busy) saving else t("Save times", "समय सेव करें", "Time save karein"), Icons.Filled.Save, {
                val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
                if (ok.matches(check) && ok.matches(prompt)) checkNote.run(scope) {
                    c.settings.setWelfareTimes(check, prompt)
                    c.welfare.scheduleDailyPrompt(prompt)
                    c.welfare.pushConfig().let { synced(it) to it }
                } else checkNote.show(t("Please write the time like 11:30", "समय ऐसे लिखिए: 11:30", "Time aise likhiye: 11:30"), ok = false)
            }, second = if (checkNote.busy) null else a("Save times", "समय सहेजें"), enabled = !checkNote.busy)
            // One test at a time: every tap messages the family.
            IconButtonBig(if (testing) t("Sending…", "भेज रहे हैं…", "Bhej rahe hain…") else t("Test welfare alert", "जाँच संदेश भेजकर देखें", "Test welfare alert"),
                Icons.AutoMirrored.Filled.Send, {
                    if (!testing) scope.launch {
                        testing = true
                        try { checkNote.show(c.welfare.sendTestAlert(), ok = null, millis = 10_000) } finally { testing = false }
                    }
                }, color = Paper, contentColor = Ink, border = Edge, enabled = !testing, height = 56.dp)
            NoteLine(checkNote)
        }

        SectionCard(Icons.Filled.Security, t("Tell family about scams", "धोखे की चेतावनी परिवार को", "Scam alert family ko"),
            t("Telegram alert on suspicious calls", "संदिग्ध कॉल पर टेलीग्राम अलर्ट", "Suspicious call par Telegram alert"),
            tint = Danger, tintBg = Danger50,
            trailing = {
                Switch(checked = s.notifyFamilyOnScam, onCheckedChange = { scope.launch { c.settings.setNotifyFamilyOnScam(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
            })

        SectionCard(Icons.Filled.Apps, t("Apps Vaani can use", "ऐप्स जिनमें वाणी काम कर सकता है", "Apps jinmein Vaani kaam kar sakta hai"),
            a("Apps Vaani can use", "वाणी किन ऐप्स में मदद करता है")) {
            Packages.ACT_ALLOWLIST.filter { it != Packages.SELF }.forEach { pkg ->
                val installed = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrElse { it !is PackageManager.NameNotFoundException }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Icon(if (installed) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = if (installed) Leaf else Muted)
                    Text(Packages.displayName(pkg), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (installed) t("installed", "इंस्टॉल है", "installed hai") else t("not installed", "इंस्टॉल नहीं", "installed nahi"),
                        style = MaterialTheme.typography.bodySmall, color = if (installed) Leaf else Muted)
                }
            }
        }

        ConnectionCard(c, health, s.useFakeBackend, s.backendUrl, style, scope)

        // Setup again: a plain row, as quiet as the History link on Home.
        val (press, pressMotion) = rememberPress()
        Surface(onClick = onSetup, interactionSource = press, shape = MaterialTheme.shapes.medium, color = Paper,
            border = BorderStroke(Border.control, Edge), modifier = Modifier.fillMaxWidth().then(pressMotion).semantics { role = Role.Button }) {
            Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Icon(Icons.Filled.Refresh, null, tint = Ink, modifier = Modifier.size(28.dp))
                TwoLine(t("Run setup again", "सेटअप फिर से", "Setup phir se"), a("Run setup again", "शुरुआती सेटअप दोबारा करें"),
                    Modifier.weight(1f))
            }
        }

        if (BuildConfig.DEBUG) DebugSection(c, style, s.replayMode)

        Text("Vaani v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectionCard(c: AppContainer, health: Result<Health>?, offline: Boolean, backendUrl: String, style: ReplyStyle, scope: CoroutineScope) {
    fun t(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val ok = health?.isSuccess
    val line = when (ok) {
        true -> t("Connected to Vaani", "वाणी से जुड़ा है", "Vaani se connected")
        false -> t("Not connected", "जुड़ा नहीं है", "Connected nahi")
        null -> t("Checking…", "जाँच रहे हैं…", "Check kar rahe hain…")
    }
    SectionCard(null, t("Connection", "कनेक्शन", "Connection"), line,
        trailing = { Icon(if (ok == false) Icons.Filled.WifiOff else Icons.Filled.Wifi, null,
            tint = if (ok == false) Danger else Leaf, modifier = Modifier.size(30.dp)) }) {
        health?.let { r ->
            InfoBox(if (r.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, healthLine(r, style), null,
                if (r.isSuccess) Leaf else Danger, if (r.isSuccess) Leaf50 else Danger50)
        }
        // Offline: its own soft panel with the switch, as in the design.
        Surface(shape = MaterialTheme.shapes.medium, color = Soft) {
            Row(Modifier.fillMaxWidth().padding(Space.lg), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                IconBubble(Icons.Filled.CloudOff, Ink, OnFill, size = 44.dp, round = true)
                TwoLine(t("Work offline", "बिना इंटरनेट", "Offline chalayein"),
                    t("Use the phone's own checks", "फ़ोन की अपनी जाँच से चलाएँ", "Phone ki apni checks se chalayein"), Modifier.weight(1f))
                Switch(checked = offline, onCheckedChange = { scope.launch { c.settings.setUseFakeBackend(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
            }
        }
        var url by remember(backendUrl) { mutableStateOf(backendUrl) }
        val urlNote = rememberNote()
        BigField(t("Server address", "सर्वर का पता", "Server ka address"), null, url) { url = it }
        IconButtonBig(t("Save address", "पता सेव करें", "Address save karein"), Icons.Filled.Save, {
            urlNote.run(scope) {
                // "192.168.1.5:8000" means http://; anything that still isn't a web address is refused, not saved.
                val raw = url.trim().let { if ("://" in it) it else "http://$it" }
                if (raw.toHttpUrlOrNull() == null) {
                    t("That isn't a server address.", "यह सर्वर का पता नहीं है।", "Yeh server ka address nahi hai.") to false
                } else {
                    c.settings.setBackendUrl(raw); url = raw
                    t("Saved.", "सेव हो गया।", "Save ho gaya.") to true
                }
            }
        }, color = Paper, contentColor = Ink, border = Edge, enabled = !urlNote.busy, height = 56.dp)
        NoteLine(urlNote)
    }
}

/** A choice button (language, demo profile): filled teal with a tick when chosen, outlined white otherwise. */
@Composable
private fun Choice(label: String, sub: String?, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (press, pressMotion) = rememberPress()
    Surface(
        onClick = onClick, interactionSource = press, shape = MaterialTheme.shapes.medium,
        color = if (chosen) MaterialTheme.colorScheme.primary else OnFill,
        contentColor = if (chosen) OnFill else Ink,
        border = if (chosen) null else BorderStroke(Border.control, Edge),
        modifier = modifier.heightIn(min = Touch.button).then(pressMotion).semantics { role = Role.RadioButton; selected = chosen },
    ) {
        Row(Modifier.padding(horizontal = Space.md, vertical = Space.md), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (chosen) Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.padding(end = Space.sm).size(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = if (chosen) OnFill.copy(alpha = 0.85f) else Muted) }
            }
        }
    }
}

/** A tinted note with a leading icon (how family joins, the Auto hint, the connection status). */
@Composable
private fun InfoBox(icon: ImageVector, text: String, second: String?, tint: Color, bg: Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = bg) {
        Row(Modifier.fillMaxWidth().padding(Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            TwoLine(text, second, Modifier.weight(1f), mainStyle = MaterialTheme.typography.bodySmall,
                mainColor = Ink)
        }
    }
}

private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

/** The Connection card's status line, in the current language. */
private fun healthLine(r: Result<Health>, style: ReplyStyle): String = r.fold(
    { h ->
        listOf(
            if (h.mock) tr(style, "Practice mode (no AI keys)", "अभ्यास मोड (AI चाबी नहीं)", "Practice mode (AI keys nahi)")
            else tr(style, "Live AI", "AI चालू", "Live AI"),
            tr(style, "voice: ", "आवाज़: ", "awaaz: ") + if (h.sarvam) "Sarvam" else tr(style, "on the phone", "फ़ोन पर", "phone par"),
            "Telegram: " + if (h.telegram) tr(style, "on", "चालू", "on") else tr(style, "log only", "सिर्फ़ लॉग", "sirf log"),
        ).joinToString(" · ")
    },
    { tr(style, "Server not reachable. Vaani is using the phone's own voice and checks.",
        "सर्वर से नहीं जुड़ पाए। वाणी फ़ोन की अपनी आवाज़ और जाँच से काम कर रहा है।",
        "Server se connect nahi ho paya. Vaani phone ki apni awaaz aur checks se kaam kar raha hai.") },
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
    note.text?.let {
        when (note.ok) {
            true -> InfoBox(Icons.Filled.CheckCircle, it, null, Leaf, Leaf50)
            false -> InfoBox(Icons.Filled.ErrorOutline, it, null, Danger, Danger50)
            null -> InfoBox(Icons.Filled.Info, it, null, Muted, Soft)
        }
    }
}

@Composable
private fun DebugSection(c: AppContainer, style: ReplyStyle, replay: Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    val report: (String) -> Unit = { status = it }
    SectionCard(Icons.Filled.Build, "Developer", tint = Muted, tintBg = Soft) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Demo replay: run tasks on recorded screens", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = replay, onCheckedChange = { scope.launch { c.settings.setReplayMode(it) } })
    }
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

/** A labelled text field: the label above (with an optional icon), a tall white box, an optional trailing icon. */
@Composable
private fun BigField(
    label: String?, icon: ImageVector?, value: String, placeholder: String? = null, trailing: ImageVector? = null,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        label?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                icon?.let { Icon(it, null, tint = Ink, modifier = Modifier.size(22.dp)) }
                Text(label, style = MaterialTheme.typography.titleSmall, color = Ink)
            }
        }
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge, color = Muted) } },
            trailingIcon = trailing?.let { { Icon(it, null, tint = Ink) } },
            textStyle = MaterialTheme.typography.bodyLarge, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Edge, focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = Paper, focusedContainerColor = Paper),
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.button),
        )
    }
}
