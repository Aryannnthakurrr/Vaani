package com.saathi.app.features.care

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saathi.app.core.AppContainer
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.BigButton
import com.saathi.app.ui.theme.Danger
import kotlinx.coroutines.delay

private typealias Tr = (String, String, String) -> String

/** Path A (workflow A1, A5, A7): siren countdown, then live ambulance tracking. Always offers "Call 108". */
@Composable
fun EmergencyScreen(c: AppContainer, onBack: () -> Unit) {
    val ui by c.emergency.ui.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val view = LocalView.current
    val activity = LocalContext.current.activity()
    DisposableEffect(Unit) {
        view.keepScreenOn = true // the wake lock of the spec: the screen stays on while help is coming
        c.emergency.screenShown = true
        onDispose {
            view.keepScreenOn = false
            // A rotation or font change rebuilds the screen at once; only a real leave counts.
            if (activity?.isChangingConfigurations != true) c.emergency.screenLeft()
        }
    }
    // Back is "I'm OK" during the countdown, and never abandons a coming ambulance (see EmergencyFlow.back).
    BackHandler { if (c.emergency.back()) onBack() }
    // The doctor flow takes over after "I'm OK": step back so the Doctor screen beneath is shown again, not stacked.
    LaunchedEffect(ui.handedOver) { if (ui.handedOver) onBack() }
    when (ui.phase) {
        EmergencyPhase.COUNTDOWN -> Countdown(ui, ::s, c)
        EmergencyPhase.NO_UNITS -> RedPage {
            val context = LocalContext.current
            Text(when (ui.failure) {
                DispatchFailure.NO_UNITS -> s("No ambulance available.\nCall 108 now.", "कोई एम्बुलेंस उपलब्ध नहीं।\nअभी 108 पर फ़ोन कीजिए।",
                    "Koi ambulance available nahi.\nAbhi 108 par call kijiye.")
                DispatchFailure.NO_LOCATION -> s("Your location could not be found.\nCall 108 now.", "आपकी लोकेशन नहीं मिली।\nअभी 108 पर फ़ोन कीजिए।",
                    "Aapki location nahi mili.\nAbhi 108 par call kijiye.")
                DispatchFailure.UNREACHABLE -> s("Could not request an ambulance.\nCall 108 now.", "एम्बुलेंस नहीं बुला पाए।\nअभी 108 पर फ़ोन कीजिए।",
                    "Ambulance nahi bula paye.\nAbhi 108 par call kijiye.")
            },
                color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            WhitePill(s("Call 108", "108 पर फ़ोन करें", "108 par call karein")) { c.emergency.call108(context) }
            if (ui.familyAlerted) {
                Text(s("Your family has been alerted.", "आपके परिवार को बता दिया गया है।", "Aapke family ko bata diya gaya hai."), color = Color.White)
            }
            TextButton(onClick = { c.emergency.close(); onBack() }) {
                Text(s("Close", "बंद करें", "Band karein"), color = Color.White, fontSize = 20.sp, textDecoration = TextDecoration.Underline)
            }
        }
        EmergencyPhase.CANCELLED -> Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val context = LocalContext.current
            com.saathi.app.ui.SuccessView(s("Cancelled", "रद्द किया गया", "Cancel kiya gaya"),
                s("No ambulance is coming.", "कोई एम्बुलेंस नहीं आ रही।", "Koi ambulance nahi aa rahi."),
                s("If you feel worse, call 108.", "तबीयत बिगड़े तो 108 पर फ़ोन कीजिए।", "Tabiyat bigde to 108 par call kijiye."))
            BigButton(s("Call 108", "108 पर फ़ोन करें", "108 par call karein"), { c.emergency.call108(context) }, color = Danger)
            BigButton(s("Close", "बंद करें", "Band karein"), { c.emergency.close(); onBack() })
        }
        else -> Tracking(ui, ::s, c, onBack)
    }
}

/** The activity behind a Compose context, which may be wrapped (theme, locale). */
private tailrec fun android.content.Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun RedPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFD32F2F), Color(0xFF8B1515))))) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun WhitePill(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Danger, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Countdown(ui: EmergencyUi, s: Tr, c: AppContainer) {
    val context = LocalContext.current
    RedPage {
        Spacer(Modifier.height(36.dp))
        Text("⚠  " + s("SENDING AN AMBULANCE IN", "एम्बुलेंस भेजी जा रही है", "AMBULANCE BHEJI JA RAHI HAI"),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        val progress by animateFloatAsState(ui.secondsLeft / ui.totalSeconds.coerceAtLeast(1).toFloat(),
            tween(950, easing = LinearEasing), label = "countdown")
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 14.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                val at = Offset(w / 2, w / 2)
                drawArc(Color.White.copy(alpha = .25f), 0f, 360f, false, at, arc, style = Stroke(w))
                drawArc(Color.White, -90f, 360f * progress, false, at, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("${ui.secondsLeft}", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(s("Your location and symptoms will be shared with the ambulance crew.",
            "आपकी लोकेशन और तकलीफ़ एम्बुलेंस टीम को भेजी जाएगी।", "Aapki location aur takleef ambulance team ko bheji jayegi."),
            color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center)
        ui.area?.let { Chip("📍  $it") }
        com.saathi.app.ui.VoiceWave({ c.assistant.voiceLevel() },
            s("Say \"I'm OK\" or \"send now\"", "\"मैं ठीक हूँ\" या \"अभी भेजो\" बोलिए", "\"Main theek hoon\" ya \"abhi bhejo\" boliye"), Color.White)
        if (ui.askLandmark) {
            var landmark by remember { mutableStateOf(c.emergency.landmark) }
            OutlinedTextField(
                value = landmark,
                onValueChange = { landmark = it; c.emergency.landmark = it },
                label = { Text(s("Address or landmark (optional)", "पता या पहचान की जगह", "Address ya landmark")) },
                modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.height(12.dp))
        // The biggest target on the screen: someone who is fine must be able to stop this instantly.
        WhitePill("✕  " + s("Cancel, I'm OK", "रोकें, मैं ठीक हूँ", "Rokein, main theek hoon")) { c.emergency.imOk() }
        TextButton(onClick = { c.emergency.sendNow() }) {
            Text(s("Send now", "अभी भेजें", "Abhi bhejein"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline)
        }
        TextButton(onClick = { c.emergency.call108(context) }) {
            Text(s("Call 108", "108 पर फ़ोन करें", "108 par call karein"), color = Color.White.copy(alpha = .85f), fontSize = 17.sp)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color(0x55000000), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 9.dp))
}

@Composable
private fun Tracking(ui: EmergencyUi, s: Tr, c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val title = when (ui.status) {
        null, "RECEIVED" -> s("Requesting an ambulance", "एम्बुलेंस बुलाई जा रही है", "Ambulance bulayi ja rahi hai")
        "ASSIGNED" -> s("Ambulance assigned", "एम्बुलेंस मिल गई", "Ambulance mil gayi")
        "EN_ROUTE" -> s("Ambulance on the way", "एम्बुलेंस आ रही है", "Ambulance aa rahi hai")
        "ARRIVED_AT_SCENE" -> s("Ambulance has arrived", "एम्बुलेंस पहुँच गई", "Ambulance pahunch gayi")
        "PATIENT_ON_BOARD" -> s("Patient on board", "मरीज़ एम्बुलेंस में हैं", "Patient ambulance mein hain")
        "EN_ROUTE_TO_HOSPITAL" -> s("Going to hospital", "अस्पताल जा रहे हैं", "Hospital ja rahe hain")
        "ARRIVED_AT_HOSPITAL" -> s("At the hospital", "अस्पताल पहुँच गए", "Hospital pahunch gaye")
        "COMPLETED" -> s("Completed", "पूरा हुआ", "Poora hua")
        else -> s("Ambulance update", "एम्बुलेंस की जानकारी", "Ambulance update") // a status this app doesn't know yet
    }
    // The ETA counts down every second between the backend's updates.
    var eta by remember { mutableIntStateOf(ui.etaSec ?: 0) }
    LaunchedEffect(ui.etaSec) {
        eta = ui.etaSec ?: return@LaunchedEffect
        while (eta > 0) { delay(1_000); eta-- }
    }
    val moving = ui.status in setOf("ASSIGNED", "EN_ROUTE", "EN_ROUTE_TO_HOSPITAL")
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(MAP_DP.dp)) {
            AmbulanceMap(ui, s("You", "आप", "Aap"), s("Ambulance", "एम्बुलेंस", "Ambulance"), Modifier.fillMaxSize())
            Text(s("Simulation: no real ambulance is sent", "सिमुलेशन: असली एम्बुलेंस नहीं भेजी गई", "Simulation: asli ambulance nahi bheji gayi"),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
                    .background(Color(0xDD2B2B2B), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 9.dp))
        }
        Column(
            Modifier.fillMaxWidth().offset(y = (-22).dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●  " + title.uppercase(), color = Danger, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                // The fleet id only: the registration number is already in the ambulance box below.
                ui.vehicleId?.takeIf { it != ui.vehicle }?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            if (moving && ui.etaSec != null) {
                Column {
                    val minutes = (eta + 30) / 60
                    Text(if (eta >= 60) s("$minutes min", "$minutes मिनट", "$minutes min") else s("$eta sec", "$eta सेकंड", "$eta sec"), fontSize = 54.sp, lineHeight = 56.sp, fontWeight = FontWeight.Black,
                        style = Tight)
                    ui.distanceM?.let { Text(s("%.1f km away", "%.1f किमी दूर", "%.1f km door").format(it / 1000.0), fontSize = 19.sp,
                        lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = Tight) }
                }
                val start = ui.startDistanceM
                val done = if (start != null && start > 0) (1f - (ui.distanceM ?: start) / start.toFloat()).coerceIn(0.02f, 1f) else 0.02f
                val shown by animateFloatAsState(done, tween(900), label = "trip")
                LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Danger,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact("🚑", when (ui.vehicleType) {
                    "ALS" -> s("ALS · Advanced Life Support", "ALS · एडवांस्ड लाइफ़ सपोर्ट", "ALS · Advanced Life Support")
                    "BLS" -> s("BLS · Basic Life Support", "BLS · बेसिक लाइफ़ सपोर्ट", "BLS · Basic Life Support")
                    else -> s("Ambulance", "एम्बुलेंस", "Ambulance")
                }, ui.vehicle, Modifier.weight(1f))
                Fact("👤", s("Driver", "ड्राइवर", "Driver"), ui.driver, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact("💉", s("Paramedic nurse", "पैरामेडिक नर्स", "Paramedic nurse"), ui.paramedic, Modifier.weight(1f))
                Fact("₹", s("Estimated fare", "अनुमानित किराया", "Estimated fare"),
                    ui.startDistanceM?.let { "₹${fare(it)} · " + s("pay later", "बाद में दें", "baad mein dein") }, Modifier.weight(1f))
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🛡  " + s("Shared with the driver and nurse", "ड्राइवर और नर्स को बताया गया", "Driver aur nurse ko bataya gaya"),
                    fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                Text(ui.brief.ifBlank { s("No symptoms described. The crew will check on arrival.",
                    "कोई तकलीफ़ नहीं बताई गई। टीम पहुँचकर जाँच करेगी।", "Koi takleef nahi batayi gayi. Team pahunch kar check karegi.") },
                    fontSize = 16.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.area?.let { Text("📍  $it", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold) }
            }
            if (ui.cancelFailed) {
                Text(s("Could not cancel. The ambulance may still come.", "रद्द नहीं हो पाया। एम्बुलेंस अब भी आ सकती है।",
                    "Cancel nahi ho paya. Ambulance ab bhi aa sakti hai."), color = Danger, fontSize = 19.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            // Once an ambulance is assigned a 108 call would only send a second one, unless cancelling it just failed.
            if (ui.status == null || ui.status == "RECEIVED" || ui.cancelFailed) {
                BigButton(s("Call 108", "108 पर फ़ोन करें", "108 par call karein"), { c.emergency.call108(context) }, color = Danger)
            }
            if (ui.cancelling) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = Danger)
                    Text(s("Cancelling the ambulance…", "एम्बुलेंस रद्द कर रहे हैं…", "Ambulance cancel kar rahe hain…"), fontSize = 18.sp)
                }
            } else if (ui.phase == EmergencyPhase.TRACKING || ui.status == "ARRIVED_AT_SCENE") { // no cancelling once on board
                TextButton(onClick = { c.emergency.imOk() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.cancelFailed) s("Try cancelling again", "फिर से रद्द करें", "Phir se cancel karein")
                        else s("I'm OK, cancel the ambulance", "मैं ठीक हूँ, एम्बुलेंस रद्द करें", "Main theek hoon, ambulance cancel karein"),
                        color = Danger, fontSize = 18.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
                }
            }
            if (ui.status == "ARRIVED_AT_HOSPITAL" || ui.status == "COMPLETED") {
                BigButton(s("Close", "बंद करें", "Band karein"), { c.emergency.close(); onBack() })
            }
            Text(s("AI-generated. Not a diagnosis.", "AI द्वारा बनाया गया। यह निदान नहीं है।", "AI se bana. Yeh diagnosis nahi hai."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** A demo estimate shown as "pay later": ₹800 base plus ₹60 a km, rounded to ₹5. Never charged by Saathi. */
private fun fare(startDistanceM: Int): Int = ((800 + 60 * startDistanceM / 1000.0) / 5).toInt() * 5

@Composable
private fun Fact(icon: String, label: String, value: String?, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).background(Danger.copy(alpha = .10f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 20.sp, color = Danger, fontWeight = FontWeight.Bold)
        }
        // Tight line heights: the app font (Mukta) has tall default lines that made these boxes sprawl.
        Column {
            Text(label, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: "…", fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MAP_DP = 430

/** Mukta adds tall font padding above and below each line; big numbers (the ETA) need it trimmed to sit tight. */
private val Tight = androidx.compose.ui.text.TextStyle(
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both),
)

@SuppressLint("SetJavaScriptEnabled") // our own bundled page; no remote content is scripted
@Composable
private fun AmbulanceMap(ui: EmergencyUi, youLabel: String, ambulanceLabel: String, modifier: Modifier) {
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
                // Map errors (tiles that fail to load, script errors) show up in logcat under "SaathiMap".
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d("SaathiMap", "${m.messageLevel()} ${m.message()} (${m.lineNumber()})")
                        return true
                    }
                }
                // Leaflet ships inside the app, so the map never depends on reaching a CDN; only the tiles are fetched.
                // An https base (not file://) so tile requests are sent like a normal web page's.
                fun asset(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }
                val html = asset("ambulance_map.html")
                    .replace(Regex("<link[^>]*leaflet[^>]*>"), Regex.escapeReplacement("<style>" + asset("leaflet/leaflet.css") + "</style>"))
                    .replace(Regex("<script src=[^>]*leaflet[^>]*></script>"), Regex.escapeReplacement("<script>" + asset("leaflet/leaflet.js") + "</script>"))
                loadDataWithBaseURL("https://saathi.local/", html, "text/html", "utf-8", null)
                web = this
            }
        },
    )
    fun js(code: String) { if (ready) web?.evaluateJavascript(code, null) }
    fun q(text: String) = "'" + text.replace("\\", "").replace("'", "\\'") + "'"
    // The page can't measure its own height inside this scrolling screen, so it is told.
    LaunchedEffect(ready) { js("setHeight($MAP_DP)") }
    LaunchedEffect(ready, ui.patient) {
        val p = ui.patient ?: return@LaunchedEffect
        js("init(${p.first}, ${p.second}, ${q(youLabel)})")
    }
    LaunchedEffect(ready, ui.route) {
        if (ui.route.isNotEmpty()) js("setRoute(${ui.route.joinToString(",", "[", "]") { "[${it[0]},${it[1]}]" }})")
    }
    LaunchedEffect(ready, ui.ambulance) {
        val a = ui.ambulance ?: return@LaunchedEffect
        js("move(${a.first}, ${a.second}, ${a.third}, ${q(ambulanceLabel)})")
    }
}
