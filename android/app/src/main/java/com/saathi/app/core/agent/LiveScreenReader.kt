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
        if (sparse && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shot = screenshot(svc)
        val secure = nodes.isEmpty() && shot == null
        return ScreenSnapshot(pkg, title, secure, nodes, screenshotB64 = shot, needsScreenshot = sparse && !secure)
    }

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
    private suspend fun screenshot(svc: AgentService): String? {
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
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
