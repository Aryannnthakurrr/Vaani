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
import kotlinx.coroutines.delay
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
        val (main, roots) = roots(svc)
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
        if (wantShot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) screenshot(svc, main)?.let { (b64, n) -> shot = b64; note = n }
        val secure = nodes.isEmpty() && shot == null
        return ScreenSnapshot(pkg, title, secure, nodes, screenshotB64 = shot, needsScreenshot = sparse && !secure, screenshotNote = note)
    }

    private var lastFingerprint: String? = null

    override suspend fun fingerprint(): String? {
        val svc = service() ?: return null
        val (_, roots) = roots(svc)
        val pkg = roots.firstOrNull()?.packageName?.toString() ?: AgentService.foreground.value
        val collected = ArrayList<NodeRef>()
        roots.forEach { walk(it, 0, collected) }
        return TreeSerializer.fingerprint(ScreenSnapshot(pkg, null, false, collected.map { it.info }))
    }

    /**
     * The app the task runs in (set by TaskRunner); kept as the screen being read while its window is visible, even
     * when one of Saathi's own windows (question card, caption) holds the focus.
     */
    @Volatile var preferredPackage: String? = null

    /**
     * The main app window first, then dialogs from other apps. The main window is the task's app when visible, else
     * the active window, else the focused application window. Saathi's windows are skipped, and so are launcher and
     * edge-panel windows behind or beside the app: taking "the first window" used to pick the Samsung launcher whenever
     * Saathi's question card had the focus, and the task stopped with "can't work in the launcher".
     */
    private fun roots(svc: AgentService): Pair<AccessibilityWindowInfo?, List<AccessibilityNodeInfo>> {
        val windows = runCatching { svc.windows }.getOrDefault(emptyList())
        fun pkgOf(n: AccessibilityNodeInfo?) = n?.packageName?.toString()
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.takeIf { pkgOf(it) != Packages.SELF }?.let { w to it } }
        val activeRoot = svc.rootInActiveWindow?.takeIf { pkgOf(it) != Packages.SELF && pkgOf(it) !in BACKGROUND_PACKAGES }
        // A React Native pop-up sheet (Pronto's slot picker) is its own window of the same app: take the top one.
        val main: Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>? = apps.filter { pkgOf(it.second) == preferredPackage }
            .maxByOrNull { it.first.layer }
            ?: activeRoot?.let { r -> apps.firstOrNull { it.second.windowId == r.windowId } ?: (null to r) }
            ?: apps.firstOrNull { it.first.isFocused && pkgOf(it.second) !in BACKGROUND_PACKAGES }
            ?: apps.firstOrNull { pkgOf(it.second) !in BACKGROUND_PACKAGES }
            ?: apps.firstOrNull()
        val dialogs = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM || it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { pkgOf(it) != Packages.SELF && pkgOf(it) !in BACKGROUND_PACKAGES && it.windowId != main?.second?.windowId }
        // Capture a single window only when the app has just that one; with a pop-up on top, the full screen is
        // captured (with Saathi's overlays hidden) so the sheet and the page behind it are both in the picture.
        val samePkg = apps.count { pkgOf(it.second) == pkgOf(main?.second) }
        return main?.first?.takeIf { samePkg == 1 } to (listOfNotNull(main?.second) + dialogs).distinctBy { it.windowId }
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

    /**
     * A JPEG (at most 1080 px tall) of what the app shows, and a note telling the model how image pixels map to taps.
     * On Android 14+ only the app's own window is captured, so Saathi's caption, mic bubble and question card never
     * cover Pronto's slots; older phones hide those overlays for the moment of the capture instead.
     */
    private suspend fun screenshot(svc: AgentService, window: AccessibilityWindowInfo?): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var offsetX = 0
        var offsetY = 0
        var bitmap: Bitmap? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && window != null) {
            val r = Rect().also { window.getBoundsInScreen(it) }
            bitmap = capture { cb -> svc.takeScreenshotOfWindow(window.id, executor, cb) }
            if (bitmap != null) { offsetX = r.left; offsetY = r.top }
        }
        if (bitmap == null) {
            val overlays = com.saathi.app.core.overlay.OverlayService.controller
            overlays?.setHiddenForCapture(true)
            try {
                if (overlays != null) delay(120) // one frame for the overlays to disappear
                bitmap = capture { cb -> svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, cb) }
            } finally {
                overlays?.setHiddenForCapture(false)
            }
        }
        val shot = bitmap ?: return null
        val scale = minOf(1f, 1080f / shot.height)
        val scaled = Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val offset = if (offsetX != 0 || offsetY != 0) ", then add $offsetX to x and $offsetY to y" else ""
        val note = "Screenshot attached: ${scaled.width}x${scaled.height} image of the app's screen. Use it for anything the " +
            "element list misses; tap_xy takes SCREEN pixels (image x and y times ${"%.2f".format(1 / scale)}$offset)."
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to note
    }

    private suspend fun capture(start: (AccessibilityService.TakeScreenshotCallback) -> Unit): Bitmap? =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    start(object : AccessibilityService.TakeScreenshotCallback {
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
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        /** React Native apps whose accessibility tree misses overlays and state changes (seen on Pronto, 2026-11-01). */
        val VISUAL_APPS = setOf(com.saathi.app.util.Packages.HOUSE_HELP)
        /** Windows that sit behind or beside the app and are never the screen a task is on. */
        val BACKGROUND_PACKAGES = setOf("com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
            "com.android.launcher3", "com.samsung.android.app.cocktailbarservice")
    }
}
