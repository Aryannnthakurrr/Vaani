package com.saathi.app.core.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.saathi.app.core.net.AgentAction
import com.saathi.app.util.Packages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Performs validated actions through the accessibility APIs (SPEC §6.2), with gesture fallbacks.
 * Validation has already happened in [SafetyPolicy]; this class never decides what is allowed.
 */
class AccessibilityActionExecutor(
    private val context: Context,
    private val service: () -> AgentService?,
    private val reader: LiveScreenReader,
) : ActionPerformer {

    /** The bottom sheet last dragged open, so the same sheet is expanded once and then scrolled. */
    private var expandedSheet: List<Int>? = null

    override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean = withContext(Dispatchers.Main) {
        val svc = service() ?: return@withContext false
        when (action.action) {
            AgentAction.TAP -> tap(svc, ref(action.id))
            AgentAction.TAP_XY -> gestureTap(svc, action.x ?: return@withContext false, action.y ?: return@withContext false)
            AgentAction.TYPE -> type(svc, ref(action.id), action.text.orEmpty())
            AgentAction.SCROLL -> scroll(svc, action.direction ?: "down", ref(action.id))
            AgentAction.BACK -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            AgentAction.HOME -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            AgentAction.OPEN_APP -> openApp(action.pkg.orEmpty())
            AgentAction.OPEN_DEEPLINK -> openUri(action.uri.orEmpty())
            else -> false
        }
    }

    private fun ref(id: Int?): NodeRef? = id?.let { reader.refs.getOrNull(it - 1) }

    private suspend fun tap(svc: AgentService, ref: NodeRef?): Boolean {
        ref ?: return false
        var n: AccessibilityNodeInfo? = ref.node.takeIf { it.refresh() }
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return gestureTap(svc, ref.info.centerX, ref.info.centerY)
    }

    private suspend fun type(svc: AgentService, ref: NodeRef?, text: String): Boolean {
        ref ?: return false
        val node = ref.node.takeIf { it.refresh() } ?: return false
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(250)
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Fallback: paste through the clipboard.
        val clipboard = svc.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("saathi", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /**
     * Scrolls the list the model named (`id`), else the biggest list that scrolls that way: a date or slot row for
     * left/right, the page for up/down. A finger swipe inside that list comes first because React Native apps
     * (Pronto) often accept the accessibility scroll action without moving; the action is only the fallback.
     */
    private suspend fun scroll(svc: AgentService, direction: String, target: NodeRef?): Boolean {
        val sideways = direction == "left" || direction == "right"
        fun w(r: NodeRef) = r.info.bounds[2] - r.info.bounds[0]
        fun h(r: NodeRef) = r.info.bounds[3] - r.info.bounds[1]
        fun horizontal(r: NodeRef) = r.info.cls.contains("Horizontal") || (w(r) > h(r) * 2 && h(r) < 900)
        val list = target?.takeIf { it.info.scrollable }
            ?: reader.refs.filter { it.info.scrollable && horizontal(it) == sideways }.maxByOrNull { w(it).toLong() * h(it) }
            ?: reader.refs.filter { it.info.scrollable }.maxByOrNull { w(it).toLong() * h(it) }
        val dm = svc.resources.displayMetrics
        val (l, t, r, b) = list?.info?.bounds ?: listOf(0, 0, dm.widthPixels, dm.heightPixels)
        // A list starting low on the screen is a pull-up sheet (Uber's ride list): a swipe inside it often moves
        // nothing. Drag the sheet up by its top edge first, once per sheet; later scrolls then move the list.
        val bounds = listOf(l, t, r, b)
        if (direction == "down" && list != null && t > dm.heightPixels * 0.35f && expandedSheet != bounds) {
            expandedSheet = bounds
            if (swipe(svc, (l + r) / 2f, t + 24f, (l + r) / 2f, dm.heightPixels * 0.12f)) return true
        }
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val dx = (r - l) * 0.35f
        val dy = (b - t) * 0.3f
        val swiped = when (direction) {
            "down" -> swipe(svc, cx, cy + dy, cx, cy - dy)
            "up" -> swipe(svc, cx, cy - dy, cx, cy + dy)
            "right" -> swipe(svc, cx + dx, cy, cx - dx, cy)
            else -> swipe(svc, cx - dx, cy, cx + dx, cy)
        }
        if (swiped) return true
        val op = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return list != null && list.node.refresh() && list.node.performAction(op)
    }

    private suspend fun gestureTap(svc: AgentService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(svc, GestureDescription.StrokeDescription(path, 0, 60))
    }

    private suspend fun swipe(svc: AgentService, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(svc, GestureDescription.StrokeDescription(path, 0, 400))
    }

    private suspend fun dispatch(svc: AgentService, stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    private fun openApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun openUri(uri: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    override suspend fun awaitSettle() {
        withTimeoutOrNull(SETTLE_MAX_MS) {
            while (true) {
                val quiet = withTimeoutOrNull(SETTLE_QUIET_MS) {
                    AgentService.events.filter { it.kind != UiEventKind.CLICKED }.first() // scrolling = not settled yet
                }
                if (quiet == null) break
            }
        }
    }

    override suspend fun awaitUserInteraction(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            AgentService.events.filter {
                // A swipe counts too: guide mode asks for scrolls as well as taps.
                it.pkg != Packages.SELF && it.kind != UiEventKind.CONTENT_CHANGED
            }.first()
            delay(SETTLE_QUIET_MS)
            true
        } ?: false

    companion object {
        const val SETTLE_QUIET_MS = 350L
        /**
         * Apps that never stop animating (Uber's live map, Pronto's banners) keep sending events, so they used to hit
         * this cap after every tap; 2 s is enough for a screen change to land.
         */
        const val SETTLE_MAX_MS = 2_000L
    }
}
