package com.saathi.app.core.overlay

import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.Answer
import com.saathi.app.core.voice.SpeechInput
import com.saathi.app.core.voice.Speaker
import com.saathi.app.core.voice.YesNo
import com.saathi.app.core.voice.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

/**
 * [AgentUi] on top of the overlays and turn-based voice. Every spoken line is also shown in large text.
 * Yes/no for the confirm gate is decided locally ([YesNo]) from speech or the on-screen buttons.
 */
class OverlayAgentUi(
    private val overlay: () -> OverlayController?,
    private val speaker: Speaker,
    private val speech: SpeechInput,
    private val style: () -> ReplyStyle,
    private val onStop: () -> Unit,
) : AgentUi {

    private fun s(en: String, hi: String, hinglish: String) = tr(style(), en, hi, hinglish)

    override suspend fun say(text: String) {
        overlay()?.showCaption(text)
        speaker.say(text, style())
    }

    override suspend fun sayPhrase(key: String) {
        speaker.sayPhrase(key, style())
        overlay()?.showCaption(speaker.caption.value)
    }

    /** Text typed elsewhere (in-app box, debug broadcast) for the question currently on screen. */
    @Volatile private var pendingText: CompletableDeferred<String?>? = null

    /** Delivers typed text to an open question or yes/no; false when nothing is waiting. */
    fun offerAnswer(text: String): Boolean = pendingText?.complete(text) == true

    /**
     * Asks by voice and on screen at once: the user may speak (two tries) or type in the answer box.
     * Whichever comes first wins. After two silent tries the box stays open until answered; Cancel returns null.
     */
    override suspend fun ask(question: String): String? = coroutineScope {
        val typed = CompletableDeferred<String?>().also { pendingText = it }
        val box = overlay()?.showAnswerBox(question) ?: CompletableDeferred()
        val byVoice = async {
            speaker.say(question, style())
            repeat(2) {
                overlay()?.showCaption(question, s("Listening… or type below", "सुन रहा हूँ… या नीचे लिखिए", "Sun raha hoon… ya neeche likhiye"))
                val heard = speech.listen()?.transcript
                if (!heard.isNullOrBlank()) return@async heard
                speaker.sayPhrase("didnt_understand", style())
            }
            awaitCancellation() // no voice: wait for the typed answer
        }
        val answer = select {
            box.onAwait { it }
            typed.onAwait { it }
            byVoice.onAwait { it }
        }
        byVoice.cancel()
        pendingText = null
        box.complete(null)
        answer?.let { overlay()?.showCaption("“$it”") }
        answer
    }

    override suspend fun confirm(summary: String): Boolean = coroutineScope {
        val controller = overlay()
        val buttons = controller?.showConfirm(summary) ?: CompletableDeferred()
        val typed = CompletableDeferred<String?>().also { pendingText = it }
        overlay()?.showCaption(summary)
        val byVoice = async { voiceYesNo(summary) }
        val byText = async {
            // Typed "haan"/"no" counts like speech; anything unclear is ignored and the sheet stays.
            when (YesNo.parse(typed.await())) {
                Answer.YES -> true
                Answer.NO -> false
                Answer.UNCLEAR -> awaitCancellation()
            }
        }
        val answer = select {
            buttons.onAwait { it }
            byVoice.onAwait { it }
            byText.onAwait { it }
        }
        byVoice.cancel()
        byText.cancel()
        pendingText = null
        speaker.stop()
        buttons.complete(answer) // dismisses the sheet if voice answered first
        answer
    }

    /**
     * Speaks the summary and listens; heard-but-unclear twice → no (SPEC §6.6). Hearing nothing at all
     * (silence, no recognizer) leaves the decision to the on-screen buttons, which stay until answered.
     */
    private suspend fun voiceYesNo(summary: String): Boolean {
        speaker.say(summary, style())
        var unclear = 0
        while (unclear < 2) {
            if (unclear > 0) speaker.sayPhrase("ask_yes_no_again", style())
            val heard = speech.listen()?.transcript ?: awaitCancellation()
            when (YesNo.parse(heard)) {
                Answer.YES -> return true
                Answer.NO -> return false
                Answer.UNCLEAR -> unclear++
            }
        }
        return false
    }

    override suspend fun waitForUserDone(prompt: String): Boolean = coroutineScope {
        val tapped = CompletableDeferred<Boolean>()
        overlay()?.showCaption(prompt, s("Waiting for you", "आपका इंतज़ार कर रहा हूँ", "Aapka wait kar raha hoon"),
            s("Done", "हो गया", "Ho gaya")) { tapped.complete(true) }
        val byVoice = async {
            // Listen in short turns until the user says "done"; the Stop chip cancels the whole run.
            while (!YesNo.isDone(speech.listen()?.transcript)) delay(300)
            true
        }
        val done = select {
            tapped.onAwait { it }
            byVoice.onAwait { it }
        }
        byVoice.cancel()
        overlay()?.showCaption(s("Thank you. Continuing.", "धन्यवाद। आगे बढ़ते हैं।", "Dhanyavaad. Aage badhte hain."))
        done
    }

    override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) {
        overlay()?.showHighlight(bounds, instruction, gesture)
    }

    override fun clearHighlight() {
        overlay()?.clearHighlight()
    }

    override fun setRunning(running: Boolean) {
        val o = overlay() ?: return
        if (running) o.showStop(onStop) else {
            o.hideStop()
            o.hideCaption()
        }
    }
}
