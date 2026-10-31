package com.saathi.app.features.care

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.InfoCard
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import kotlinx.coroutines.delay

/** Path A (workflow A1, A5, A7): siren countdown, then live ambulance tracking. Always offers "Call 108". */
@Composable
fun EmergencyScreen(c: AppContainer, onBack: () -> Unit) {
    val ui by c.emergency.ui.collectAsState()
    val style = c.settings.current.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // the wake lock of the spec: the screen stays on while help is coming
        onDispose { view.keepScreenOn = false }
    }
    val red = ui.phase == EmergencyPhase.COUNTDOWN || ui.phase == EmergencyPhase.NO_UNITS
    Column(
        Modifier.fillMaxSize().background(if (red) Danger else MaterialTheme.colorScheme.background)
            .systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (ui.phase) {
            EmergencyPhase.COUNTDOWN -> Countdown(ui, ::s, c)
            EmergencyPhase.NO_UNITS -> {
                Text(s("No ambulance available.\nCall 108 now.", "कोई एम्बुलेंस उपलब्ध नहीं।\nअभी 108 पर फ़ोन कीजिए।",
                    "Koi ambulance available nahi.\nAbhi 108 par call kijiye."),
                    color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                BigButton(s("📞 Call 108", "📞 108 पर फ़ोन करें", "📞 108 par call karein"), { c.emergency.call108(context) },
                    Modifier.heightIn(min = 140.dp), color = Color.White, contentColor = Danger)
                if (ui.familyAlerted) {
                    Text(s("Your family has been alerted.", "आपके परिवार को बता दिया गया है।", "Aapke family ko bata diya gaya hai."), color = Color.White)
                }
            }
            EmergencyPhase.CANCELLED -> {
                InfoCard(s("Cancelled", "रद्द किया गया", "Cancel kiya gaya"),
                    s("No ambulance is coming. If you feel worse, call 108.", "कोई एम्बुलेंस नहीं आ रही। तबीयत बिगड़े तो 108 पर फ़ोन कीजिए।",
                        "Koi ambulance nahi aa rahi. Tabiyat bigde to 108 par call kijiye."), icon = "✅")
                BigButton(s("📞 Call 108", "📞 108 पर फ़ोन करें", "📞 108 par call karein"), { c.emergency.call108(context) }, color = Danger)
                BigButton(s("Close", "बंद करें", "Band karein"), { c.emergency.close(); onBack() })
            }
            else -> Tracking(ui, ::s, c, onBack)
        }
        Text(s("AI-generated. Not a diagnosis.", "AI द्वारा बनाया गया। यह निदान नहीं है।", "AI se bana. Yeh diagnosis nahi hai."),
            color = if (red) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun Countdown(ui: EmergencyUi, s: (String, String, String) -> String, c: AppContainer) {
    val context = LocalContext.current
    Text(s("Sending an ambulance in", "एम्बुलेंस भेजी जाएगी", "Ambulance bheji jayegi"), color = Color.White, fontSize = 24.sp)
    Text("${ui.secondsLeft}", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    com.saathi.app.ui.VoiceWave({ c.assistant.voiceLevel() },
        s("Say \"I'm OK\" or \"send now\"", "\"मैं ठीक हूँ\" या \"अभी भेजो\" बोलिए", "\"Main theek hoon\" ya \"abhi bhejo\" boliye"), Color.White)
    // The biggest target on the screen: someone who is fine must be able to stop this instantly.
    BigButton(s("I'M OK, CANCEL", "मैं ठीक हूँ, रोकें", "Main theek hoon, rokein"), { c.emergency.imOk() },
        Modifier.heightIn(min = 160.dp), color = Leaf, contentColor = Color.White)
    BigButton(s("SEND AMBULANCE NOW", "अभी एम्बुलेंस भेजें", "Abhi ambulance bhejein"), { c.emergency.sendNow() },
        color = Color.White, contentColor = Danger)
    BigButton(s("📞 Call 108", "📞 108 पर फ़ोन करें", "📞 108 par call karein"), { c.emergency.call108(context) },
        color = Color(0xFF7F0000), contentColor = Color.White)
    if (ui.askLandmark) {
        var landmark by remember { mutableStateOf(c.emergency.landmark) }
        OutlinedTextField(
            value = landmark,
            onValueChange = { landmark = it; c.emergency.landmark = it },
            label = { Text(s("Address or landmark (optional)", "पता या पहचान की जगह", "Address ya landmark")) },
            modifier = Modifier.fillMaxWidth().background(Color.White),
        )
    }
    if (ui.brief.isNotBlank()) Text(ui.brief, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
    ui.topCondition?.let { Text(s("Possible: $it", "संभव: $it", "Possible: $it"), color = Color.White, fontSize = 15.sp) }
}

@Composable
private fun Tracking(ui: EmergencyUi, s: (String, String, String) -> String, c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val title = when (ui.status) {
        null, "RECEIVED" -> s("Requesting an ambulance…", "एम्बुलेंस बुलाई जा रही है…", "Ambulance bulayi ja rahi hai…")
        "ASSIGNED" -> s("Ambulance assigned", "एम्बुलेंस मिल गई", "Ambulance mil gayi")
        "EN_ROUTE" -> s("On the way", "रास्ते में है", "Raste mein hai")
        "ARRIVED_AT_SCENE" -> s("Arrived", "पहुँच गई", "Pahunch gayi")
        "PATIENT_ON_BOARD" -> s("Patient on board", "मरीज़ एम्बुलेंस में हैं", "Patient ambulance mein hain")
        "EN_ROUTE_TO_HOSPITAL" -> s("Going to hospital", "अस्पताल जा रहे हैं", "Hospital ja rahe hain")
        "ARRIVED_AT_HOSPITAL" -> s("At the hospital", "अस्पताल पहुँच गए", "Hospital pahunch gaye")
        "COMPLETED" -> s("Completed", "पूरा हुआ", "Poora hua")
        else -> ui.status.replace('_', ' ').lowercase()
    }
    // The ETA counts down every second between the backend's updates.
    var eta by remember { mutableIntStateOf(ui.etaSec ?: 0) }
    LaunchedEffect(ui.etaSec) {
        eta = ui.etaSec ?: return@LaunchedEffect
        while (eta > 0) { delay(1_000); eta-- }
    }
    val etaText = ui.etaSec?.let { s("ETA ${eta / 60} min ${eta % 60} s", "${eta / 60} मिनट ${eta % 60} सेकंड में", "${eta / 60} min ${eta % 60} sec mein") }
    val km = ui.distanceM?.let { "%.1f km".format(it / 1000.0) }
    InfoCard(title, listOfNotNull(etaText, km, ui.vehicle, ui.crew).joinToString(" · ").ifBlank { null }, accent = Danger, icon = "🚑")
    Text(STEPS.joinToString("   ") { (if (it in ui.timeline) "✔ " else "○ ") + it.replace('_', ' ').lowercase() }, fontSize = 15.sp)
    AmbulanceMap(ui, Modifier.fillMaxWidth().height(340.dp))
    if (ui.status == null || ui.status == "RECEIVED") { // once an ambulance is assigned, a 108 call would only send a second one
        BigButton(s("📞 Call 108", "📞 108 पर फ़ोन करें", "📞 108 par call karein"), { c.emergency.call108(context) }, color = Danger)
    }
    if (ui.phase == EmergencyPhase.TRACKING || ui.status == "ARRIVED_AT_SCENE") { // no cancelling once on board
        BigButton(s("I'm OK, cancel the ambulance", "मैं ठीक हूँ, एम्बुलेंस रद्द करें", "Main theek hoon, ambulance cancel karein"),
            { c.emergency.imOk() }, color = Leaf)
    }
    if (ui.status == "ARRIVED_AT_HOSPITAL" || ui.status == "COMPLETED") {
        BigButton(s("Close", "बंद करें", "Band karein"), { c.emergency.close(); onBack() })
    }
}

private val STEPS = listOf("RECEIVED", "ASSIGNED", "EN_ROUTE", "ARRIVED_AT_SCENE", "PATIENT_ON_BOARD",
    "EN_ROUTE_TO_HOSPITAL", "ARRIVED_AT_HOSPITAL", "COMPLETED")

@SuppressLint("SetJavaScriptEnabled") // our own bundled page; no remote content is scripted
@Composable
private fun AmbulanceMap(ui: EmergencyUi, modifier: Modifier) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { ready = true }
                }
                // An https base (not file://) so the map library and tile requests are sent like a normal web page's.
                val html = ctx.assets.open("ambulance_map.html").bufferedReader().use { it.readText() }
                loadDataWithBaseURL("https://saathi.local/", html, "text/html", "utf-8", null)
                web = this
            }
        },
    )
    LaunchedEffect(ready, ui.patient) {
        val p = ui.patient ?: return@LaunchedEffect
        if (ready) web?.evaluateJavascript("init(${p.first}, ${p.second})", null)
    }
    LaunchedEffect(ready, ui.route) {
        if (ready && ui.route.isNotEmpty()) {
            web?.evaluateJavascript("setRoute(${ui.route.joinToString(",", "[", "]") { "[${it[0]},${it[1]}]" }})", null)
        }
    }
    LaunchedEffect(ready, ui.ambulance) {
        val a = ui.ambulance ?: return@LaunchedEffect
        if (ready) web?.evaluateJavascript("move(${a.first}, ${a.second}, ${a.third})", null)
    }
}
