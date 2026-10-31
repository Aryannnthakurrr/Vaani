package com.saathi.app

import com.saathi.app.core.agent.ScreenNode
import com.saathi.app.core.agent.ScreenSnapshot
import com.saathi.app.core.agent.TreeSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSerializerTest {

    @Test
    fun serializesUberHomeInContractFormat() {
        val text = TreeSerializer.serialize(Fixtures.screens("uber_book")[0])
        val lines = text.lines()
        assertEquals("package=com.ubercab title=\"Uber\" secure=false", lines[0])
        assertEquals("  [1] Button \"Where to?\" clickable bounds=(40,820,1040,940)", lines[1])
    }

    @Test
    fun editableHintsAndCheckState() {
        val search = TreeSerializer.serialize(Fixtures.screens("uber_book")[1])
        assertTrue(search, search.contains("[3] EditText hint=\"Where to?\" clickable editable focused bounds="))
        val display = TreeSerializer.serialize(Fixtures.screens("settings_dark_mode")[1])
        assertTrue(display.contains("Switch \"Dark theme\" clickable unchecked"))
        val dark = TreeSerializer.serialize(Fixtures.screens("settings_dark_mode")[2])
        assertTrue(dark.contains("Switch \"Dark theme\" clickable checked"))
    }

    @Test
    fun everyFixtureSerializesWithIdsInOrder() {
        for (scenario in Fixtures.scenarios()) {
            for (screen in Fixtures.screens(scenario)) {
                val lines = TreeSerializer.serialize(screen).lines().drop(1)
                assertEquals(screen.nodes.size, lines.size)
                lines.forEachIndexed { i, l -> assertTrue(l, l.trimStart().startsWith("[${i + 1}] ")) }
            }
        }
    }

    @Test
    fun capDropsDeepNonInteractiveTextFirst() {
        val nodes = (1..200).map { i ->
            if (i % 4 == 0) ScreenNode("Button", "b$i", clickable = true, depth = 1) else ScreenNode("TextView", "t$i", depth = if (i < 100) 9 else 2)
        }
        val capped = TreeSerializer.cap(nodes)
        assertEquals(TreeSerializer.MAX_NODES, capped.size)
        assertEquals(50, capped.count { it.clickable })
        assertTrue(capped.none { it.depth == 9 && capped.size == 150 && it.text == "t1" })
        assertEquals(capped, capped.sortedBy { n -> nodes.indexOf(n) })
    }

    @Test
    fun longTextIsTruncatedAndQuotesEscaped() {
        val s = ScreenSnapshot("p", "t", false, listOf(ScreenNode("TextView", "say \"hi\" " + "x".repeat(200))))
        val line = TreeSerializer.serialize(s).lines()[1]
        assertTrue(line.contains("say 'hi'"))
        assertTrue(line.length < 140)
    }

    @Test
    fun fingerprintChangesWithCheckedState() {
        val screens = Fixtures.screens("settings_dark_mode")
        assertNotEquals(TreeSerializer.fingerprint(screens[1]), TreeSerializer.fingerprint(screens[2]))
        assertEquals(TreeSerializer.fingerprint(screens[1]), TreeSerializer.fingerprint(screens[1].copy()))
    }
}
