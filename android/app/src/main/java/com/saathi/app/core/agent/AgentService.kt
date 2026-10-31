package com.saathi.app.core.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.saathi.app.SaathiApp
import com.saathi.app.core.overlay.OverlayService
import com.saathi.app.util.Packages
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Kinds of accessibility events the agent loop waits on. */
enum class UiEventKind { CONTENT_CHANGED, WINDOW_CHANGED, CLICKED, SCROLLED }

data class UiEvent(val kind: UiEventKind, val pkg: String?, val ts: Long)

/**
 * Saathi's AccessibilityService: the eyes ([LiveScreenReader]) and hands ([AccessibilityActionExecutor])
 * of the agent, plus a foreground-app feed for CallGuard.
 */
class AgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        Log.i(TAG, "accessibility service connected")
        OverlayService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> UiEventKind.WINDOW_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> UiEventKind.CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> UiEventKind.CLICKED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> UiEventKind.SCROLLED
            else -> return
        }
        if (pkg == Packages.SELF && kind != UiEventKind.WINDOW_CHANGED) return
        _events.tryEmit(UiEvent(kind, pkg, System.currentTimeMillis()))
        if (kind == UiEventKind.WINDOW_CHANGED && pkg != null && pkg != _foreground.value) {
            _foreground.value = pkg
            Log.d(TAG, "foreground: $pkg")
            runCatching { SaathiApp.container.callGuard.onForegroundPackage(pkg) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SaathiAgent"

        @Volatile
        var instance: AgentService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        private val _foreground = MutableStateFlow("")
        val foreground: StateFlow<String> = _foreground

        private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val events: SharedFlow<UiEvent> = _events
    }
}
