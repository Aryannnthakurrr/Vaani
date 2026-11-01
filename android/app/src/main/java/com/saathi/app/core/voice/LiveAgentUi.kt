package com.saathi.app.core.voice

import com.saathi.app.core.AssistantScreen
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.overlay.OverlayAgentUi
import com.saathi.app.core.privacy.PrivateSpans
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [AgentUi] for a task started from the live conversation: Saathi keeps talking and listening in real time instead
 * of dropping to turn-based speech. Gemini only *voices* the questions; every answer is read on the phone from the
 * user's own words (their transcript) or the on-screen buttons, so a booking can never be confirmed by the model.
 * Safety phrases and private values are spoken on-device with the mic off (never through the cloud). Screen-only
 * parts (captions, highlights, the Stop chip, the confirm sheet) come from [screen].
 */
class LiveAgentUi(
    private val live: LiveVoiceSession,
    private val screen: OverlayAgentUi,
) : AgentUi {

    override suspend fun say(text: String) {
        if (PrivateSpans.TOKEN.containsMatchIn(text)) return live.handOffPrivately { screen.say(text) }
        screen.caption(text)
        voice("[say exactly, then stop: $text]")
    }

    override suspend fun sayPhrase(key: String) = live.handOffPrivately { screen.sayPhrase(key) }

    override suspend fun ask(question: String): String? = screen.withPrompt(AssistantScreen.Prompt.Ask(question)) { box ->
        coroutineScope {
            voice("[ask the user exactly this, then stop and let them answer: $question]")
            val heard = async {
                SpokenAnswer.freeText(listen = { nextTurn() }, reprompt = { voice("[they are quiet: gently ask again: $question]") })
                    ?: awaitCancellation()
            }
            val answer = select { box.answer.onAwait { it }; heard.onAwait { it } }
            heard.cancel()
            answer
        }
    }

    override suspend fun confirm(summary: String): Boolean = screen.withPrompt(AssistantScreen.Prompt.Confirm(summary)) { sheet ->
        coroutineScope {
            voice("[read this to the user and ask for a yes or no, then stop. Do not say it is done: $summary]")
            val spoken = async {
                SpokenAnswer.yesNo(
                    listen = { nextTurn() },
                    reprompt = { voice("[ask them to please say yes or no]") },
                ) ?: awaitCancellation()
            }
            val answer = select { sheet.answer.onAwait { it }; spoken.onAwait { it } }
            spoken.cancel()
            answer
        }
    }

    /** PIN / password entry: the live mic is off until Done is tapped, so nothing said meanwhile reaches the cloud. */
    override suspend fun waitForUserDone(prompt: String): Boolean = screen.withDoneButton(prompt) { tapped ->
        live.handOffPrivately { tapped.await() }
    }

    override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) = screen.showHighlight(bounds, instruction, gesture)
    override fun clearHighlight() = screen.clearHighlight()
    override fun setRunning(running: Boolean) = screen.setRunning(running)

    /** Has Saathi say something in the live voice and waits (briefly) until it has finished saying it. */
    private suspend fun voice(note: String) {
        live.sendText(note)
        withTimeoutOrNull(SPEAK_START_MS) { live.state.first { it == LiveState.SPEAKING } } ?: return
        withTimeoutOrNull(SPEAK_MAX_MS) { live.state.first { it != LiveState.SPEAKING } }
    }

    /** The user's next finished turn, or null after [TURN_MS] of silence. */
    private suspend fun nextTurn(): String? = withTimeoutOrNull(TURN_MS) { live.userTurns.first() }

    private companion object {
        const val SPEAK_START_MS = 6_000L
        const val SPEAK_MAX_MS = 20_000L
        const val TURN_MS = 8_000L
    }
}
