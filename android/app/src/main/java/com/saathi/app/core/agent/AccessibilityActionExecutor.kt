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

    override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean = withContext(Dispatchers.Main) {
        val svc = service() ?: return@withContext false
        when (action.action) {
            AgentAction.TAP -> tap(svc, ref(action.id))
            AgentAction.TAP_XY -> gestureTap(svc, action.x ?: return@withContext false, action.y ?: return@withContext false)
            AgentAction.TYPE -> type(svc, ref(action.id), action.text.orEmpty())
            AgentAction.SCROLL -> scroll(svc, action.direction ?: "down")
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

    private suspend fun scroll(svc: AgentService, direction: String): Boolean {
        val scrollable = reader.refs.filter { it.info.scrollable }
            .maxByOrNull { (it.info.bounds[2] - it.info.bounds[0]) * (it.info.bounds[3] - it.info.bounds[1]) }
        val forward = direction == "down" || direction == "right"
        val op = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable != null && scrollable.node.refresh() && scrollable.node.performAction(op)) return true
        val dm = svc.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val d = dm.heightPixels / 3f
        return when (direction) {
            "down" -> swipe(svc, cx, cy + d, cx, cy - d)
            "up" -> swipe(svc, cx, cy - d, cx, cy + d)
            "right" -> swipe(svc, cx + d, cy, cx - d, cy)
            else -> swipe(svc, cx - d, cy, cx + d, cy)
        }
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
        const val SETTLE_QUIET_MS = 600L
        const val SETTLE_MAX_MS = 5_000L
    }
}
