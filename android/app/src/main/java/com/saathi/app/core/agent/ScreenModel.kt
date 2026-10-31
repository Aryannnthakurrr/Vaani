package com.saathi.app.core.agent

import kotlinx.serialization.Serializable

/**
 * One on-screen element as recorded in `fixtures/screens` and as produced by the live reader.
 * [bounds] is `[left, top, right, bottom]` in screen pixels.
 */
@Serializable
data class ScreenNode(
    val cls: String,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: List<Int> = listOf(0, 0, 0, 0),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val depth: Int = 0,
) {
    val interactive: Boolean get() = clickable || editable || scrollable || checkable
    val label: String get() = listOfNotNull(text, desc, hint).firstOrNull { it.isNotBlank() } ?: ""
    val centerX: Int get() = (bounds[0] + bounds[2]) / 2
    val centerY: Int get() = (bounds[1] + bounds[3]) / 2

    fun contains(x: Int, y: Int) = x in bounds[0]..bounds[2] && y in bounds[1]..bounds[3]

    /** Every human-visible string on the node, for safety regexes. */
    fun allText(): String = listOfNotNull(text, desc, hint).joinToString(" ")
}

/** File format of a fixture screen (docs/CONTRACTS.md). */
@Serializable
data class ScreenFixture(
    val `package`: String,
    val title: String? = null,
    val secure: Boolean = false,
    val nodes: List<ScreenNode> = emptyList(),
)

/**
 * A snapshot of the current screen. Node IDs are 1-based positions in [nodes]
 * (assigned fresh for every snapshot).
 */
data class ScreenSnapshot(
    val pkg: String,
    val title: String?,
    val secure: Boolean,
    val nodes: List<ScreenNode>,
    val screenshotB64: String? = null,
    val needsScreenshot: Boolean = false,
    /** How the screenshot's pixels map to tap_xy coordinates; sent to the model above the element list. */
    val screenshotNote: String? = null,
) {
    fun node(id: Int?): ScreenNode? = id?.let { nodes.getOrNull(it - 1) }

    fun nodeAt(x: Int, y: Int): ScreenNode? = nodes.filter { it.contains(x, y) }.maxByOrNull { it.depth }

    val interactiveCount: Int get() = nodes.count { it.interactive }

    companion object {
        fun from(f: ScreenFixture) = ScreenSnapshot(f.`package`, f.title, f.secure, f.nodes)
    }
}
