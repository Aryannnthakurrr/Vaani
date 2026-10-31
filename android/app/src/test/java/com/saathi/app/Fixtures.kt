package com.saathi.app

import com.saathi.app.core.agent.MockAgentScripts
import com.saathi.app.core.agent.ReplayScreenReader
import com.saathi.app.core.agent.ScreenSnapshot
import java.io.File

/** Reads the repo-level `fixtures/` folder from JVM tests (Gradle runs them with the module as working dir). */
object Fixtures {
    val root: File = listOf(File("../../fixtures"), File("../fixtures"), File("fixtures")).first { it.isDirectory }

    fun screens(scenario: String): List<ScreenSnapshot> =
        ReplayScreenReader.parseScreens(File(root, "screens/$scenario").listFiles()!!.sortedBy { it.name }.map { it.readText() })

    fun safety(name: String): ScreenSnapshot = ReplayScreenReader.parseScreens(listOf(File(root, "screens/safety/$name.json").readText())).single()

    fun scripts(): MockAgentScripts = MockAgentScripts.parse(File(root, "agent_scripts").listFiles()!!.map { it.readText() })

    fun scenarios(): List<String> = File(root, "agent_scripts").listFiles()!!.map { it.nameWithoutExtension }.sorted()
}
