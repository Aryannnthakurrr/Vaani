package com.saathi.app

import com.saathi.app.core.agent.AccessibilityActionExecutor
import com.saathi.app.core.agent.AgentLoop
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.agent.ReplayPerformer
import com.saathi.app.core.agent.ReplayScreenReader
import com.saathi.app.core.agent.TaskContext
import com.saathi.app.core.agent.UiEvent
import com.saathi.app.core.agent.UiEventKind
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.FakeBackendClient
import com.saathi.app.core.net.ReplyStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time the user waits during a task, measured in virtual time: nothing here may stall the agent needlessly. */
class AgentSpeedTest {

    /** Progress lines take [speakMs] to say (synthesis + playback); everything else is instant. */
    private class SlowSpeakingUi(val speakMs: Long) : AgentUi {
        var progressLines = 0
        override suspend fun say(text: String) {
            if (text.endsWith("…")) { progressLines++; delay(speakMs) }
        }
        override suspend fun sayPhrase(key: String) = Unit
        override suspend fun ask(question: String) = "Dr. Mehta"
        override suspend fun confirm(summary: String) = true
        override suspend fun waitForUserDone(prompt: String) = true
        override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) = Unit
        override fun clearHighlight() = Unit
        override fun setRunning(running: Boolean) = Unit
    }

    @Test
    fun progressLinesAreSpokenWhileTheAgentKeepsWorking() = runTest {
        val scripts = Fixtures.scripts()
        val ui = SlowSpeakingUi(speakMs = 3_000)
        val performer = ReplayPerformer(pauseMs = 2_000) // each tap/type takes 2 s of real work
        val outcome = AgentLoop({ FakeBackendClient({ scripts }, { null }) }, ReplayScreenReader(Fixtures.screens("uber_book")),
            performer, ui, { ReplyStyle.EN })
            .run("book a cab to Apollo", AgentMode.ACT, TaskContext(taskHint = "uber_book"))
        assertTrue("$outcome", outcome is AgentOutcome.Done)
        assertTrue("the scenario must speak at least one progress line", ui.progressLines > 0)
        val work = performer.performed.size * 2_000L
        // Speaking blocked the loop if every progress line added its 3 s on top of the work.
        assertTrue("took ${currentTime} ms for $work ms of work and ${ui.progressLines} progress lines",
            currentTime < work + ui.progressLines * 3_000L)
    }

    @Test
    fun aScreenThatNeverStopsMovingDoesNotHoldUpEachStep() = runTest {
        // Uber's home screen has a live map: content-changed events keep coming forever.
        val liveMap = flow { while (true) { delay(100); emit(UiEvent(UiEventKind.CONTENT_CHANGED, "com.ubercab", 0)) } }
        val started = currentTime
        AccessibilityActionExecutor.awaitQuiet(liveMap)
        val waited = currentTime - started
        assertTrue("waited $waited ms after a tap on a moving screen", waited <= 2_000)
    }
}
