package com.saathi.app.core.overlay

import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.privacy.PrivateSpans
import com.saathi.app.core.voice.Answer
import com.saathi.app.core.voice.SpeechInput
import com.saathi.app.core.voice.Speaker
import com.saathi.app.core.voice.SpokenAnswer
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
     * Asks by voice and on screen at once: the user may speak or type in the answer box; whichever comes first
     * wins. The question is spoken with a cue, and each listen starts with a beep. Silence re-prompts rather than
     * ending the listening ([SpokenAnswer]); after about a minute the box stays open until answered. Cancel → null.
     */
    override suspend fun ask(question: String): String? = coroutineScope {
        val typed = CompletableDeferred<String?>().also { pendingText = it }
        val box = overlay()?.showAnswerBox(question) ?: CompletableDeferred()
        val byVoice = async {
            speaker.say(question, style())
            speaker.sayPhrase("answer_cue", style())
            val heard = SpokenAnswer.freeText(
                listen = {
                    overlay()?.showCaption(question, s("Listening… or type below", "सुन रहा हूँ… या नीचे लिखिए", "Sun raha hoon… ya neeche likhiye"))
                    speech.listen(cue = true)?.transcript
                },
                reprompt = { speaker.sayPhrase("still_listening", style()) },
            )
            heard ?: run {
                speaker.sayPhrase("waiting_for_tap", style())
                awaitCancellation() // no voice: wait for the typed answer
            }
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
     * Speaks the summary and "please say yes or no", then listens after a beep. Silence re-prompts and listens
     * again; heard-but-unclear twice → no (SPEC §6.6). About a minute of silence leaves the decision to the
     * on-screen buttons, which stay until answered.
     */
    private suspend fun voiceYesNo(summary: String): Boolean {
        speaker.say(summary, style())
        speaker.sayPhrase("ask_yes_no_again", style())
        val answer = SpokenAnswer.yesNo(
            listen = { speech.listen(cue = true)?.transcript },
            reprompt = { speaker.sayPhrase("ask_yes_no_again", style()) },
        )
        if (answer != null) return answer
        speaker.sayPhrase("waiting_for_tap", style())
        awaitCancellation()
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

    // ------------------------------------------------------------------ screen-only parts, for LiveAgentUi

    fun caption(text: String) {
        overlay()?.showCaption(PrivateSpans.TOKEN.replace(text, "••••"))
    }

    /** The answer box, plus typed text from elsewhere (debug broadcast); completes with the text or null (Cancel). */
    fun openAnswerBox(question: String): CompletableDeferred<String?> {
        val result = CompletableDeferred<String?>()
        val typed = CompletableDeferred<String?>().also { pendingText = it }
        val box = overlay()?.showAnswerBox(question)
        box?.invokeOnCompletion { box.getCompleted().let(result::complete) }
        typed.invokeOnCompletion { typed.getCompleted().let(result::complete) }
        overlay()?.showCaption(question, s("Listening… or type below", "सुन रहा हूँ… या नीचे लिखिए", "Sun raha hoon… ya neeche likhiye"))
        currentBox = box
        return result
    }

    fun closeAnswerBox() {
        pendingText = null
        currentBox?.complete(null)
        currentBox = null
    }

    /** The confirm sheet; completes with a button tap, or a typed "haan"/"no". Unclear typed text is ignored. */
    fun openConfirm(summary: String): CompletableDeferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        val buttons = overlay()?.showConfirm(summary)
        buttons?.invokeOnCompletion { result.complete(buttons.getCompleted()) }
        pendingText = CompletableDeferred<String?>().also { t ->
            t.invokeOnCompletion {
                when (YesNo.parse(t.getCompleted())) {
                    Answer.YES -> result.complete(true)
                    Answer.NO -> result.complete(false)
                    Answer.UNCLEAR -> Unit
                }
            }
        }
        overlay()?.showCaption(summary)
        currentSheet = buttons
        return result
    }

    /** Dismisses the sheet with the decision, whoever made it. */
    fun closeConfirm(answer: Boolean) {
        pendingText = null
        currentSheet?.complete(answer)
        currentSheet = null
    }

    fun openDoneButton(prompt: String): CompletableDeferred<Boolean> {
        val tapped = CompletableDeferred<Boolean>()
        overlay()?.showCaption(prompt, s("Waiting for you", "आपका इंतज़ार कर रहा हूँ", "Aapka wait kar raha hoon"),
            s("Done", "हो गया", "Ho gaya")) { tapped.complete(true) }
        return tapped
    }

    private var currentBox: CompletableDeferred<String?>? = null
    private var currentSheet: CompletableDeferred<Boolean>? = null

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
