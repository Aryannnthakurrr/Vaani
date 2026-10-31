package com.saathi.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the mic bubble and the Home talk button show. */
enum class VoiceStatus { IDLE, CONNECTING, LISTENING, SPEAKING, WORKING }

/**
 * Everything the assistant shows on screen, in one place. Flows only write here; they never add or remove windows
 * themselves. Over other apps the overlay windows draw it (OverlayController); inside Saathi the in-app sheet draws
 * it (ui/AssistantSheet.kt). A run's end clears it in one call, so nothing is left behind.
 */
class AssistantScreen {
    /** Large text of what Saathi is saying. [button] adds one big action ("Done"). */
    data class Caption(val text: String, val status: String = "", val button: String? = null, val onButton: (() -> Unit)? = null)

    /** A question waiting for the user; speech, the on-screen controls or typed text complete [answer]. */
    sealed class Prompt {
        class Ask(val question: String, val answer: CompletableDeferred<String?> = CompletableDeferred()) : Prompt()
        class Confirm(val summary: String, val answer: CompletableDeferred<Boolean> = CompletableDeferred()) : Prompt()
    }

    data class Highlight(val bounds: List<Int>?, val instruction: String, val gesture: String?)

    /** How a task ended: a green tick or an amber notice, shown for a few seconds. */
    data class Result(val ok: Boolean, val message: String, val id: Long = System.nanoTime())

    private val _caption = MutableStateFlow<Caption?>(null)
    val caption: StateFlow<Caption?> = _caption
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt
    /** A task is running: the Stop chip shows and the caption shrinks to a slim bar. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _highlight = MutableStateFlow<Highlight?>(null)
    val highlight: StateFlow<Highlight?> = _highlight
    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result
    /** Saathi's own screen is in front: the in-app sheet draws, the overlay windows stay away. */
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    fun caption(c: Caption?) { _caption.value = c }
    fun caption(text: String, status: String = "") { _caption.value = Caption(text, status) }
    fun running(on: Boolean) { _running.value = on }
    fun highlight(h: Highlight?) { _highlight.value = h }
    fun foreground(on: Boolean) { _foreground.value = on }

    fun result(r: Result?) { _result.value = r }
    /** Clears [r] only if it is still the one showing (a newer result stays). */
    fun clearResult(r: Result) { _result.compareAndSet(r, null) }

    fun prompt(p: Prompt) {
        _prompt.value?.let(::dismiss)
        _prompt.value = p
    }

    /** Takes [p] off the screen if it is still the current one, completing it as cancelled. */
    fun endPrompt(p: Prompt) {
        dismiss(p)
        _prompt.compareAndSet(p, null)
    }

    /** Typed text for the open question: the answer to an Ask, or "haan"/"no" for a Confirm. False if nothing waits. */
    fun offer(text: String, yesNo: (String) -> Boolean?): Boolean = when (val p = _prompt.value) {
        is Prompt.Ask -> p.answer.complete(text)
        is Prompt.Confirm -> yesNo(text)?.let(p.answer::complete) ?: true // unclear: swallowed, the sheet stays
        null -> false
    }

    /** The end of a run: everything but the result card goes. */
    fun clear() {
        _prompt.value?.let(::dismiss)
        _prompt.value = null
        _caption.value = null
        _highlight.value = null
        _running.value = false
    }

    private fun dismiss(p: Prompt) {
        when (p) {
            is Prompt.Ask -> p.answer.complete(null)
            is Prompt.Confirm -> p.answer.complete(false)
        }
    }
}
