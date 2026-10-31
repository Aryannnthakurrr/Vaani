package com.saathi.app.core.agent

/**
 * Guide mode: the app the goal names ("send it on WhatsApp"), when it is already on the screen. The model sometimes
 * says "swipe up" on a share sheet that shows WhatsApp; the loop points at the visible icon instead.
 */
object GuideTarget {
    private val APPS = listOf("WhatsApp", "Telegram", "Gmail", "Messages", "Instagram", "Facebook", "Signal")

    fun app(goal: String): String? = APPS.firstOrNull { goal.contains(it, ignoreCase = true) }

    /** The tappable element for [app] on [snap]: the smallest clickable box around its label, else the label. */
    fun visible(app: String, snap: ScreenSnapshot): ScreenNode? {
        val label = snap.nodes.firstOrNull { n ->
            listOfNotNull(n.text, n.desc).any { it.trim().equals(app, ignoreCase = true) || it.trim().startsWith("$app ", ignoreCase = true) }
        } ?: return null
        return snap.nodes.filter { it.clickable && it.contains(label.centerX, label.centerY) }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) } ?: label
    }
}
