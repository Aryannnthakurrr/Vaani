package com.saathi.app.features.guard

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saathi.app.core.guard.TextBox
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the text on a screenshot with ML Kit's bundled, on-device recognizer, for the booking price check
 * (core/guard/PriceGuard). Nothing is sent anywhere and nothing is logged; an error or a slow read gives no lines,
 * which the check treats as "couldn't read prices" (the booking goes ahead as the user agreed).
 */
class OcrScreen(private val capture: suspend () -> Bitmap?) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun lines(): List<TextBox> {
        val bitmap = capture() ?: return emptyList()
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        val out = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { b -> TextBox(line.text, b.left, b.top, b.right, b.bottom) }
                        }
                        if (cont.isActive) cont.resume(out)
                    }
                    .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
            }
        }.orEmpty()
    }

    private companion object {
        const val TIMEOUT_MS = 2_500L
    }
}
