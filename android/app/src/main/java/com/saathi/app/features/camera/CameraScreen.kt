package com.saathi.app.features.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.ProductCard
import com.saathi.app.core.net.Slots
import com.saathi.app.core.voice.tr
import com.saathi.app.features.tasks.Tasks
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import java.util.concurrent.Executors

/** Full-screen CameraX preview with the product card at the bottom (SPEC §7.3). */
@Composable
fun CameraScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val session = remember { CameraSession(container) }
    val card by session.card.collectAsState()
    val caption by session.caption.collectAsState()
    val ended by session.ended.collectAsState()
    val muted by session.muted.collectAsState()
    val mic by session.mic.collectAsState()
    val style = container.settings.flow.collectAsState().value.replyStyle
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraFailed by remember { mutableStateOf(false) }

    // Camera permission: ask on open; if refused, say so plainly with a way to allow it (no black screen).
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasCamera()) }
    var asked by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    DisposableEffect(owner) {
        // Back from the app's settings page with the camera now allowed.
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasCamera() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }
    DisposableEffect(granted) {
        if (granted) session.start()
        onDispose { session.stop() }
    }
    LaunchedEffect(ended) { if (ended) onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera view" },
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()
                            analysis.setAnalyzer(analysisExecutor) { image ->
                                image.use { proxy -> session.onFrame { runCatching { proxy.toBitmap() }.getOrNull() } }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }.onFailure { cameraFailed = true }
                    }, ContextCompat.getMainExecutor(context))
                }
            },
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val message = when {
                !granted -> tr(style, "Saathi needs the camera to read the pack. Please allow it.",
                    "पैकेट पढ़ने के लिए साथी को कैमरा चाहिए। कृपया इजाज़त दीजिए।",
                    "Packet padhne ke liye Saathi ko camera chahiye. Please allow kijiye.")
                cameraFailed -> tr(style, "The camera could not start. Please close and try again.",
                    "कैमरा चालू नहीं हो पाया। बंद करके फिर से कोशिश कीजिए।", "Camera chalu nahi ho paaya. Band karke phir se try kijiye.")
                else -> caption
            }
            if (message.isNotBlank()) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.92f)) {
                    Text(message, style = MaterialTheme.typography.titleMedium, color = Color.Black,
                        modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            if (!granted) {
                CamButton(tr(style, "📷 Allow camera", "📷 कैमरा की इजाज़त दें", "📷 Camera allow karein"), Leaf, Modifier.fillMaxWidth()) {
                    // After "don't ask again" the system shows no dialog: open the app's settings page instead.
                    val activity = context as? Activity
                    if (asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } else {
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                }
            }
            card?.let { ProductCardView(it, style = style, onReadAgain = session::readAgain, onOrder = {
                // Stop first, so leaving the camera can't cut off the task's first line.
                session.stop()
                container.assistant.startTask(Tasks.SHOP, Slots(product = it.name))
                onBack()
            }) }
            // Mute only does something while Saathi is listening; otherwise say why it can't hear.
            val micNote = when (mic) {
                CameraSession.Mic.OFFLINE -> tr(style, "Asking by voice needs the internet. Saathi will still read the pack.",
                    "बोलकर पूछने के लिए इंटरनेट चाहिए। साथी फिर भी पैकेट पढ़ेगा।", "Bolkar poochne ke liye internet chahiye. Saathi phir bhi packet padhega.")
                CameraSession.Mic.UNAVAILABLE -> tr(style, "The microphone is not allowed, so Saathi can't hear you here. It will still read the pack.",
                    "माइक की इजाज़त नहीं है, इसलिए साथी यहाँ आपकी बात नहीं सुन सकता। फिर भी पैकेट पढ़ेगा।",
                    "Mic ki permission nahi hai, isliye Saathi yahan aapki baat nahi sun sakta. Phir bhi packet padhega.")
                else -> null
            }
            if (granted && micNote != null) {
                Text(micNote, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mic) {
                    CameraSession.Mic.LISTENING -> CamButton(
                        if (muted) tr(style, "🎤 Unmute", "🎤 माइक चालू", "🎤 Mic on") else tr(style, "🔇 Mute", "🔇 माइक बंद", "🔇 Mic band"),
                        Color(0xFF3B2F27), Modifier.weight(1f)) { session.muted.value = !muted }
                    CameraSession.Mic.LOST -> CamButton(tr(style, "🔄 Retry", "🔄 फिर से", "🔄 Retry"), Leaf, Modifier.weight(1f), session::retry)
                    else -> Unit
                }
                CamButton(tr(style, "Close", "बंद करें", "Band karein"), Danger, Modifier.weight(1f), onBack)
            }
        }
    }
}

@Composable
private fun ProductCardView(card: ProductCard, style: com.saathi.app.core.net.ReplyStyle, onReadAgain: () -> Unit, onOrder: () -> Unit) {
    val (badge, color) = when (card.expiryStatus) {
        "expired" -> tr(style, "EXPIRED", "एक्सपायर", "EXPIRED") to Danger
        "soon" -> tr(style, "Expires soon", "जल्दी एक्सपायर", "Jaldi expire") to Amber
        "ok" -> tr(style, "Fresh", "ठीक है", "Theek hai") to Leaf
        else -> tr(style, "Expiry not read", "एक्सपायरी नहीं पढ़ी", "Expiry nahi padhi") to Color.Gray
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.name ?: "…", style = MaterialTheme.typography.headlineMedium)
            Text(listOfNotNull(card.quantity, card.mrpText?.let { "MRP $it" }).joinToString("  ·  "), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = color, shape = RoundedCornerShape(12.dp)) {
                    Text(badge, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                card.expiryIso?.let { Text(tr(style, "Expiry: $it", "एक्सपायरी: $it", "Expiry: $it"), style = MaterialTheme.typography.bodyLarge) }
            }
            card.healthWarning?.let { warning ->
                Surface(color = if (card.healthLevel == "high") Danger else Amber, shape = RoundedCornerShape(16.dp)) {
                    Text("⚠ $warning", color = Color.White, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CamButton(tr(style, "🔊 Read again", "🔊 फिर से सुनें", "🔊 Phir se sunein"), MaterialTheme.colorScheme.primary, Modifier.weight(1f), onReadAgain)
                CamButton(tr(style, "🛒 Order online", "🛒 ऑनलाइन मंगाएँ", "🛒 Order karein"), Leaf, Modifier.weight(1f), onOrder)
            }
        }
    }
}

@Composable
private fun CamButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 72.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
