package com.saathi.app.core.overlay

import com.saathi.app.core.AssistantScreen
import com.saathi.app.core.AssistantScreen.Prompt
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
 * [AgentUi] on top of [AssistantScreen] and turn-based voice. Every spoken line is also shown in large text.
 * Yes/no for the confirm gate is decided locally ([YesNo]) from speech, the on-screen buttons or typed text.
 * Every question is taken off the screen in a `finally`, so Stop or an error never leaves a dead card behind.
 */
class OverlayAgentUi(
    private val screen: AssistantScreen,
    private val speaker: Speaker,
    private val speech: SpeechInput,
    private val style: () -> ReplyStyle,
) : AgentUi {

    private fun s(en: String, hi: String, hinglish: String) = tr(style(), en, hi, hinglish)

    override suspend fun say(text: String) {
        caption(text)
        speaker.say(text, style())
    }

    override suspend fun sayPhrase(key: String) {
        speaker.sayPhrase(key, style())
        caption(speaker.caption.value)
    }

    /** Delivers typed text to an open question or yes/no; false when nothing is waiting. */
    fun offerAnswer(text: String): Boolean = screen.offer(text) { yesNo(it) }

    /**
     * Asks by voice and on screen at once: the user may speak or type in the answer box; whichever comes first
     * wins. The question is spoken with a cue, and each listen starts with a beep. Silence re-prompts rather than
     * ending the listening ([SpokenAnswer]); after about a minute the box stays open until answered. Cancel → null.
     */
    override suspend fun ask(question: String): String? = withPrompt(Prompt.Ask(question)) { box ->
        coroutineScope {
            val byVoice = async {
                speaker.say(question, style())
                speaker.sayPhrase("answer_cue", style())
                SpokenAnswer.freeText(
                    listen = { speech.listen(cue = true)?.transcript },
                    reprompt = { speaker.sayPhrase("still_listening", style()) },
                ) ?: run {
                    speaker.sayPhrase("waiting_for_tap", style())
                    awaitCancellation() // no voice: wait for the box
                }
            }
            val answer = select {
                box.answer.onAwait { it }
                byVoice.onAwait { it }
            }
            byVoice.cancel()
            answer
        }
    }

    override suspend fun confirm(summary: String): Boolean = withPrompt(Prompt.Confirm(summary)) { sheet ->
        coroutineScope {
            val byVoice = async { voiceYesNo(summary) }
            val answer = select {
                sheet.answer.onAwait { it }
                byVoice.onAwait { it }
            }
            byVoice.cancel()
            speaker.stop()
            answer
        }
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

    /**
     * Used while the user types a PIN, OTP or password themselves. The mic stays off the whole time (someone may
     * read the PIN aloud as they type it, and speech goes to the cloud): only the Done button ends the wait. The
     * Stop chip still cancels the whole run.
     */
    override suspend fun waitForUserDone(prompt: String): Boolean = withDoneButton(prompt) { tapped -> tapped.await() }

    // ------------------------------------------------------------------ shared with LiveAgentUi

    fun caption(text: String) {
        screen.caption(PrivateSpans.TOKEN.replace(text, "••••"))
    }

    /** Shows [p] for as long as [block] runs, and always takes it off the screen afterwards. */
    suspend fun <P : Prompt, T> withPrompt(p: P, block: suspend (P) -> T): T {
        screen.prompt(p)
        try {
            return block(p)
        } finally {
            screen.endPrompt(p)
        }
    }

    /** The caption with a big "Done" button while [block] runs; completes the deferred on tap. */
    suspend fun <T> withDoneButton(prompt: String, block: suspend (CompletableDeferred<Boolean>) -> T): T {
        val tapped = CompletableDeferred<Boolean>()
        val card = AssistantScreen.Caption(prompt, s("Waiting for you", "आपका इंतज़ार कर रही हूँ", "Aapka wait kar rahi hoon"),
            s("Done", "हो गया", "Ho gaya")) { tapped.complete(true) }
        screen.caption(card)
        try {
            return block(tapped)
        } finally {
            if (screen.caption.value === card) {
                screen.caption(s("Thank you. Continuing.", "धन्यवाद। आगे बढ़ते हैं।", "Dhanyavaad. Aage badhte hain."))
            }
        }
    }

    override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) {
        screen.highlight(AssistantScreen.Highlight(bounds, instruction, gesture))
        screen.caption(instruction, s("Your turn", "अब आप दबाइए", "Ab aap dabaiye"))
    }

    override fun clearHighlight() {
        screen.highlight(null)
    }

    /** Only the start counts: a run's end is cleaned up by the Assistant, which knows whether a newer run began. */
    override fun setRunning(running: Boolean) {
        if (running) screen.running(true)
    }

    companion object {
        fun yesNo(text: String): Boolean? = when (YesNo.parse(text)) {
            Answer.YES -> true
            Answer.NO -> false
            Answer.UNCLEAR -> null
        }
    }
}
