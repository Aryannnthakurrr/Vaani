package com.saathi.app.core.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.saathi.app.util.Packages
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** A node reference that survives as long as the snapshot; [find] re-locates it if the view went stale. */
class NodeRef(val node: AccessibilityNodeInfo, val info: ScreenNode)

/**
 * Reads the active window (plus dialogs/overlays from other apps) through [AgentService] (SPEC §6.1).
 * Saathi's own overlay windows are skipped so the model never sees our bubble.
 */
class LiveScreenReader(private val service: () -> AgentService?) : ScreenSource {

    @Volatile
    var refs: List<NodeRef> = emptyList()
        private set

    override suspend fun read(): ScreenSnapshot {
        val svc = service() ?: return ScreenSnapshot("unknown", null, secure = false, nodes = emptyList())
        val roots = roots(svc)
        val pkg = roots.firstOrNull()?.packageName?.toString() ?: AgentService.foreground.value
        val collected = ArrayList<NodeRef>()
        roots.forEach { walk(it, 0, collected) }
        refs = TreeSerializer.capIndices(collected.map { it.info }).map { collected[it] }

        val nodes = refs.map { it.info }
        val title = roots.firstOrNull()?.window?.title?.toString()
        var shot: String? = null
        val sparse = nodes.count { it.interactive } < 3
        // The element list can't be trusted alone when it is nearly empty, when it hasn't changed since the last
        // read (an action that "did nothing" often did something the app didn't report: Pronto's "Add" turns into
        // "Added" and a "Go to cart" bar appears, neither of which reaches accessibility), or in apps known to hide
        // parts of their screen. Then the model also gets a screenshot.
        val fingerprint = TreeSerializer.fingerprint(ScreenSnapshot(pkg, title, false, nodes))
        val unchanged = fingerprint == lastFingerprint
        lastFingerprint = fingerprint
        val wantShot = sparse || unchanged || pkg in VISUAL_APPS
        var note: String? = null
        if (wantShot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) screenshot(svc)?.let { (b64, n) -> shot = b64; note = n }
        val secure = nodes.isEmpty() && shot == null
        return ScreenSnapshot(pkg, title, secure, nodes, screenshotB64 = shot, needsScreenshot = sparse && !secure, screenshotNote = note)
    }

    private var lastFingerprint: String? = null

    private fun roots(svc: AgentService): List<AccessibilityNodeInfo> {
        val active = svc.rootInActiveWindow
        val others = runCatching {
            svc.windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }.mapNotNull { it.root }
        }.getOrDefault(emptyList())
        return (listOfNotNull(active) + others)
            .filter { it.packageName?.toString() != Packages.SELF }
            .distinctBy { it.windowId }
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<NodeRef>) {
        if (!n.isVisibleToUser) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val text = n.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString()?.takeIf { it.isNotBlank() } else null
        val keep = !r.isEmpty && (n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || text != null || desc != null)
        if (keep) {
            out += NodeRef(n, ScreenNode(
                cls = n.className?.toString()?.substringAfterLast('.') ?: "View",
                text = if (n.isPassword) null else text,
                desc = desc, hint = hint, viewId = n.viewIdResourceName,
                bounds = listOf(r.left, r.top, r.right, r.bottom),
                clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, focused = n.isFocused,
                password = n.isPassword, depth = depth,
            ))
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, if (keep) depth + 1 else depth, out) }
    }

    /** Downscaled JPEG (max 1080 px tall, q70) for sparse trees. Null for FLAG_SECURE windows. */
    /** A JPEG of the screen (at most 1080 px tall) and a note telling the model how image pixels map to taps. */
    private suspend fun screenshot(svc: AgentService): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resume(soft)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } ?: return null
        val scale = minOf(1f, 1080f / bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val note = "Screenshot attached: ${scaled.width}x${scaled.height} image of the ${bitmap.width}x${bitmap.height} screen. " +
            "Use it for anything the element list misses; tap_xy takes SCREEN pixels (image x and y times ${"%.2f".format(1 / scale)})."
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to note
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        /** React Native apps whose accessibility tree misses overlays and state changes (seen on Pronto, 2026-11-01). */
        val VISUAL_APPS = setOf(com.saathi.app.util.Packages.HOUSE_HELP)
    }
}
