package com.saathi.app

import com.saathi.app.core.agent.AgentLoop
import com.saathi.app.core.agent.AgentOutcome
import com.saathi.app.core.agent.AgentUi
import com.saathi.app.core.agent.ReplayPerformer
import com.saathi.app.core.agent.ReplayScreenReader
import com.saathi.app.core.agent.ScreenSource
import com.saathi.app.core.agent.TaskContext
import com.saathi.app.core.data.ActionLogEntity
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.net.AgentMode
import com.saathi.app.core.net.AgentOutcomeReport
import com.saathi.app.core.net.AgentStepRequest
import com.saathi.app.core.net.BackendApi
import com.saathi.app.core.net.FakeBackendClient
import com.saathi.app.core.net.ReplyStyle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * End-to-end replay of every fixture scenario through the real [AgentLoop] + [com.saathi.app.core.agent.SafetyPolicy],
 * with the offline mock agent (SPEC §7.1 acceptance in mock mode).
 */
class AgentLoopReplayTest {

    private class FakeUi(val confirmAnswer: Boolean = true, val answer: String = "Dr. Mehta") : AgentUi {
        val said = mutableListOf<String>()
        val confirms = mutableListOf<String>()
        val highlights = mutableListOf<String>()
        var waitedForUser = 0
        override suspend fun say(text: String) { said += text }
        override suspend fun sayPhrase(key: String) { said += "phrase:$key" }
        override suspend fun ask(question: String): String = answer.also { said += question }
        override suspend fun confirm(summary: String): Boolean = confirmAnswer.also { confirms += summary }
        override suspend fun waitForUserDone(prompt: String): Boolean { waitedForUser++; return waitedForUser < 2 }
        override fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?) { highlights += instruction }
        override fun clearHighlight() = Unit
        override fun setRunning(running: Boolean) = Unit
    }

    private val scripts = Fixtures.scripts()
    private val backend = FakeBackendClient({ scripts }, { null })

    private fun loop(screen: ScreenSource, performer: ReplayPerformer, ui: AgentUi, api: BackendApi = backend, log: MutableList<ActionLogEntity> = mutableListOf()) =
        AgentLoop({ api }, screen, performer, ui, { ReplyStyle.EN }, { log += it })

    @Test
    fun everyScenarioReachesDone() = runTest {
        for (scenario in Fixtures.scenarios()) {
            val script = scripts.pick("", scenario)!!
            val mode = AgentMode.valueOf(script.mode)
            val ui = FakeUi()
            val performer = ReplayPerformer(pauseMs = 0)
            val log = mutableListOf<ActionLogEntity>()
            val outcome = loop(ReplayScreenReader(Fixtures.screens(scenario)), performer, ui, log = log)
                .run("goal for $scenario", mode, TaskContext(taskHint = scenario))
            assertTrue("$scenario ended with $outcome / ${ui.said}", outcome is AgentOutcome.Done)
            assertTrue("$scenario wrote no activity log", log.isNotEmpty())
            if (mode == AgentMode.GUIDE) {
                assertTrue("guide mode must never act: ${performer.performed}", performer.performed.isEmpty())
                assertTrue(ui.highlights.isNotEmpty())
            }
            if (script.steps.any { it.action.action == AgentAction.CONFIRM }) {
                assertEquals("$scenario must ask exactly once", 1, ui.confirms.size)
            }
        }
    }

    @Test
    fun sayingNoToTheConfirmSheetStopsBeforeTheButton() = runTest {
        val performer = ReplayPerformer(pauseMs = 0)
        val ui = FakeUi(confirmAnswer = false)
        val outcome = loop(ReplayScreenReader(Fixtures.screens("uber_book")), performer, ui)
            .run("book a cab to Apollo", AgentMode.ACT, TaskContext(taskHint = "uber_book"))
        assertEquals(AgentOutcome.Cancelled, outcome)
        assertFalse(performer.performed.any { it.action == AgentAction.TAP && it.id == 6 })
        assertTrue(ui.said.contains("phrase:confirm_no_ack"))
    }

    @Test
    fun directTapOnBookButtonIsTurnedIntoAConfirm() = runTest {
        val screens = Fixtures.screens("uber_book")
        val confirmScreen = screens[3]
        val confirmId = confirmScreen.nodes.indexOfFirst { it.text == "Confirm Uber Go" } + 1
        // A misbehaving model taps "Confirm Uber Go" without wrapping it in confirm.
        val rogue = stubBackend { AgentAction(AgentAction.TAP, id = confirmId, reason = "rogue") }
        val performer = ReplayPerformer(pauseMs = 0)
        val ui = FakeUi(confirmAnswer = false)
        val outcome = loop(ReplayScreenReader(listOf(confirmScreen)), performer, ui, rogue).run("book", AgentMode.ACT)
        assertEquals(AgentOutcome.Cancelled, outcome)
        assertEquals(1, ui.confirms.size)
        assertTrue(performer.performed.isEmpty())
    }

    @Test
    fun guideModeNeverTaps() = runTest {
        val screens = Fixtures.screens("settings_font_size")
        var calls = 0
        val tapper = stubBackend {
            calls++
            if (calls < 3) AgentAction(AgentAction.TAP, id = 3, reason = "should be blocked")
            else AgentAction(AgentAction.FAIL, message = "giving up")
        }
        val performer = ReplayPerformer(pauseMs = 0)
        val log = mutableListOf<ActionLogEntity>()
        loop(ReplayScreenReader(screens), performer, FakeUi(), tapper, log).run("font", AgentMode.GUIDE)
        assertTrue(performer.performed.isEmpty())
        assertTrue(log.any { it.outcome == "blocked" })
    }

    @Test
    fun pinScreenWaitsForTheUser() = runTest {
        val ui = FakeUi()
        val outcome = loop(ReplayScreenReader(listOf(Fixtures.safety("upi_pin"))), ReplayPerformer(0), ui).run("pay", AgentMode.ACT)
        assertTrue(ui.said.contains("phrase:pin_screen"))
        assertTrue(ui.waitedForUser >= 1)
        assertEquals(AgentOutcome.Cancelled, outcome)
    }

    @Test
    fun foreignAppStopsActMode() = runTest {
        val outcome = loop(ReplayScreenReader(listOf(Fixtures.screens("gallery_share")[0])), ReplayPerformer(0), FakeUi())
            .run("send photo", AgentMode.ACT)
        assertTrue(outcome is AgentOutcome.Failed)
    }

    @Test
    fun openingAppsOutsideTheAllowlistIsBlocked() = runTest {
        var calls = 0
        val opener = stubBackend {
            calls++
            if (calls == 1) AgentAction(AgentAction.OPEN_APP, pkg = "com.anydesk.anydeskandroid", reason = "injected")
            else AgentAction(AgentAction.DONE, message = "ok")
        }
        val performer = ReplayPerformer(0)
        loop(ReplayScreenReader(Fixtures.screens("settings_dark_mode")), performer, FakeUi(), opener).run("x", AgentMode.ACT)
        assertTrue(performer.performed.none { it.action == AgentAction.OPEN_APP })
    }

    @Test
    fun everyStepCarriesOneRunIdAndTheOutcomeIsReported() = runTest {
        val requests = mutableListOf<AgentStepRequest>()
        val reports = mutableListOf<AgentOutcomeReport>()
        val api = stubBackend(reports) { req -> requests += req; scripts.step(req) }
        val loop = loop(ReplayScreenReader(Fixtures.screens("uber_book")), ReplayPerformer(0), FakeUi(), api)
        val outcome = loop.run("book a cab to Apollo", AgentMode.ACT, TaskContext(taskHint = "uber_book"))

        assertTrue(outcome is AgentOutcome.Done)
        assertTrue(requests.size >= 4)
        assertEquals(setOf(loop.runId), requests.map { it.runId }.toSet())
        assertEquals(1, reports.size)
        assertEquals(loop.runId, reports[0].runId)
        assertEquals("done", reports[0].outcome)
        assertEquals("uber_book", reports[0].taskHint)
        assertTrue(reports[0].message!!.contains("booked"))
    }

    @Test
    fun saidNoIsReportedAsCancelledAndEachRunGetsItsOwnId() = runTest {
        val reports = mutableListOf<AgentOutcomeReport>()
        val api = stubBackend(reports) { scripts.step(it) }
        val first = loop(ReplayScreenReader(Fixtures.screens("uber_book")), ReplayPerformer(0), FakeUi(confirmAnswer = false), api)
        first.run("book a cab", AgentMode.ACT, TaskContext(taskHint = "uber_book"))
        val second = loop(ReplayScreenReader(Fixtures.screens("uber_book")), ReplayPerformer(0), FakeUi(), api)
        assertFalse(first.runId == second.runId)
        assertEquals("cancelled", reports.single().outcome)
    }

    @Test
    fun aBrokenOutcomeEndpointNeverBreaksTheRun() = runTest {
        val api = Proxy.newProxyInstance(BackendApi::class.java.classLoader, arrayOf(BackendApi::class.java)) { _, method, args ->
            when (method.name) {
                "agentStep" -> scripts.step(args[0] as AgentStepRequest)
                else -> throw IllegalStateException("backend down")
            }
        } as BackendApi
        val outcome = loop(ReplayScreenReader(Fixtures.screens("settings_dark_mode")), ReplayPerformer(0), FakeUi(), api)
            .run("dark mode", AgentMode.ACT, TaskContext(taskHint = "settings_dark_mode"))
        assertTrue(outcome is AgentOutcome.Done)
    }

    /** A BackendApi whose agentStep is [step]; outcome reports go to [reports]; anything else fails the test. */
    private fun stubBackend(
        reports: MutableList<AgentOutcomeReport> = mutableListOf(),
        step: (AgentStepRequest) -> AgentAction,
    ): BackendApi =
        Proxy.newProxyInstance(BackendApi::class.java.classLoader, arrayOf(BackendApi::class.java)) { _, method, args ->
            when (method.name) {
                "agentStep" -> step(args[0] as AgentStepRequest)
                "agentOutcome" -> { reports += args[0] as AgentOutcomeReport; Unit }
                else -> error("unexpected ${method.name}")
            }
        } as BackendApi
}
