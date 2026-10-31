package com.saathi.app.core.agent

import com.saathi.app.core.net.AgentAction

/** Where screens come from: the live accessibility tree, or recorded fixtures in replay mode. */
interface ScreenSource {
    suspend fun read(): ScreenSnapshot

    /** Called after an action changed (or should have changed) the screen. Replay advances here. */
    fun onActionExecuted(action: AgentAction) = Unit

    /**
     * A cheap signature of what is on screen now (no screenshot, nothing remembered), used in guide mode to notice that
     * the user has done the step. Null when the source can't tell (replay).
     */
    suspend fun fingerprint(): String? = null
}

/** Performs actions on the device. */
interface ActionPerformer {
    /** Executes one already-validated action. Returns false when the action could not be carried out. */
    suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean

    /** Waits for the screen to settle (600 ms quiet, 5 s max). */
    suspend fun awaitSettle()

    /** GUIDE mode: waits for the user to tap something or the window to change. False on timeout. */
    suspend fun awaitUserInteraction(timeoutMs: Long): Boolean
}

/** Everything the loop needs from the overlay + voice layer. */
interface AgentUi {
    suspend fun say(text: String)
    suspend fun sayPhrase(key: String)

    /** Speaks [question], listens, returns the transcript; null when the user cancelled. */
    suspend fun ask(question: String): String?

    /** Shows the confirm sheet and listens for yes/no. Only an explicit yes returns true. */
    suspend fun confirm(summary: String): Boolean

    /** Pauses while the user does something themselves (PIN, login); true when they say "done". */
    suspend fun waitForUserDone(prompt: String): Boolean

    fun showHighlight(bounds: List<Int>?, instruction: String, gesture: String?)
    fun clearHighlight()
    fun setRunning(running: Boolean)
}
