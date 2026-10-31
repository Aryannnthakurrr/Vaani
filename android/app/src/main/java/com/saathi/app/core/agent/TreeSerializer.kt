package com.saathi.app.core.agent

/**
 * Turns a [ScreenSnapshot] into the compact text the model reads (SPEC §6.1, docs/CONTRACTS.md):
 *
 * ```
 * package=com.ubercab title="Uber" secure=false
 * [14] Button "Where to?" clickable bounds=(40,820,1040,940)
 * ```
 */
object TreeSerializer {
    const val MAX_NODES = 150
    private const val MAX_TEXT = 80

    fun serialize(s: ScreenSnapshot): String = buildString {
        append("package=").append(s.pkg)
        append(" title=").append(quote(s.title ?: ""))
        append(" secure=").append(s.secure)
        if (s.needsScreenshot) append(" needs_screenshot=true")
        s.nodes.forEachIndexed { i, n ->
            append('\n')
            append("  ".repeat(n.depth.coerceIn(0, 12)))
            append('[').append(i + 1).append("] ").append(n.cls.substringAfterLast('.'))
            n.text?.takeIf { it.isNotBlank() }?.let { append(' ').append(quote(it)) }
            n.desc?.takeIf { it.isNotBlank() && it != n.text }?.let { append(" desc=").append(quote(it)) }
            n.hint?.takeIf { it.isNotBlank() && it != n.text }?.let { append(" hint=").append(quote(it)) }
            if (n.clickable) append(" clickable")
            if (n.editable) append(" editable")
            if (n.scrollable) append(" scrollable")
            if (n.checkable) append(if (n.checked) " checked" else " unchecked")
            if (n.focused) append(" focused")
            if (n.password) append(" password")
            val b = n.bounds
            append(" bounds=(").append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append(',').append(b[3]).append(')')
        }
    }

    /**
     * Keeps at most [MAX_NODES] nodes, dropping the deepest non-interactive text first, while preserving
     * the original (pre-order) order of the survivors.
     */
    fun cap(nodes: List<ScreenNode>, max: Int = MAX_NODES): List<ScreenNode> = capIndices(nodes, max).map { nodes[it] }

    /** Indices (ascending) of the nodes [cap] keeps. */
    fun capIndices(nodes: List<ScreenNode>, max: Int = MAX_NODES): List<Int> {
        if (nodes.size <= max) return nodes.indices.toList()
        val dropOrder = nodes.withIndex().filter { !it.value.interactive }.sortedByDescending { it.value.depth }
        val drop = dropOrder.take(nodes.size - max).map { it.index }.toHashSet()
        return nodes.indices.filter { it !in drop }.take(max)
    }

    /** Package + hash of node texts: used to detect "no progress" loops. */
    fun fingerprint(s: ScreenSnapshot): String =
        s.pkg + ":" + s.nodes.joinToString("|") { it.label + if (it.checked) "*" else "" }.hashCode()

    private fun quote(t: String): String {
        val clean = t.replace('\n', ' ').replace("\"", "'").trim()
        return "\"" + (if (clean.length > MAX_TEXT) clean.take(MAX_TEXT - 1) + "…" else clean) + "\""
    }
}
