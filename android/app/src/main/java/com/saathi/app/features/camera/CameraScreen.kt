package com.saathi.app.features.camera

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
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
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
    val style = container.settings.flow.collectAsState().value.replyStyle
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        session.start()
        onDispose {
            session.stop()
            analysisExecutor.shutdown()
        }
    }
    LaunchedEffect(ended) { if (ended) onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera view" },
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            image.use { proxy -> session.onFrame { runCatching { proxy.toBitmap() }.getOrNull() } }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            },
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (caption.isNotBlank()) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.92f)) {
                    Text(caption, style = MaterialTheme.typography.titleMedium, color = Color.Black,
                        modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            card?.let { ProductCardView(it, style = style, onReadAgain = session::readAgain, onOrder = {
                container.assistant.startTask(Tasks.SHOP, Slots(product = it.name))
                onBack()
            }) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CamButton(if (muted) tr(style, "🎤 Unmute", "🎤 माइक चालू", "🎤 Mic on") else tr(style, "🔇 Mute", "🔇 माइक बंद", "🔇 Mic band"),
                    Color(0xFF3B2F27), Modifier.weight(1f)) { session.muted.value = !muted }
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
