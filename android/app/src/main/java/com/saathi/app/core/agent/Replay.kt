package com.saathi.app.core.agent

import android.content.res.AssetManager
import com.saathi.app.core.net.AgentAction
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.delay

/**
 * Debug replay (SPEC §9.3): feeds a recorded scenario from `fixtures/screens/<scenario>/NN.json` to the
 * agent loop, advancing one screen after every action that would change the real screen.
 */
class ReplayScreenReader(private val screens: List<ScreenSnapshot>) : ScreenSource {
    var index = 0
        private set

    override suspend fun read(): ScreenSnapshot = screens[index.coerceAtMost(screens.lastIndex)]

    override fun onActionExecuted(action: AgentAction) {
        if (action.action in ADVANCING && index < screens.lastIndex) index++
    }

    companion object {
        /** Actions that move a scenario forward. `highlight` stands in for the user's tap in guide mode. */
        val ADVANCING = setOf(AgentAction.TAP, AgentAction.TAP_XY, AgentAction.TYPE, AgentAction.SCROLL, AgentAction.BACK,
            AgentAction.OPEN_APP, AgentAction.OPEN_DEEPLINK, AgentAction.HIGHLIGHT)

        fun parseScreens(jsonTexts: List<String>): List<ScreenSnapshot> =
            jsonTexts.map { ScreenSnapshot.from(SaathiJson.decodeFromString(ScreenFixture.serializer(), it)) }

        /** Loads a scenario bundled as debug assets; empty in release builds. */
        fun fromAssets(assets: AssetManager, scenario: String): ReplayScreenReader? {
            val names = runCatching { assets.list("screens/$scenario")?.sorted() }.getOrNull().orEmpty()
            if (names.isEmpty()) return null
            return ReplayScreenReader(parseScreens(names.map { n -> assets.open("screens/$scenario/$n").bufferedReader().use { it.readText() } }))
        }

        fun scenarios(assets: AssetManager): List<String> =
            runCatching { assets.list("screens")?.filter { it != "safety" } }.getOrNull().orEmpty()
    }
}

/** Replay "hands": every action succeeds after a short, visible pause. */
class ReplayPerformer(private val pauseMs: Long = 700) : ActionPerformer {
    val performed = mutableListOf<AgentAction>()

    override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean {
        performed += action
        delay(pauseMs)
        return true
    }

    override suspend fun awaitSettle() = Unit

    override suspend fun awaitUserInteraction(timeoutMs: Long): Boolean {
        delay(pauseMs * 2)
        return true
    }
}
