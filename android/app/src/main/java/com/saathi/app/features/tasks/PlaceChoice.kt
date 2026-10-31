package com.saathi.app.features.tasks

import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr

/**
 * When a destination matches places in different cities ("Apollo Hospital": Delhi, Faridabad, Gurugram), Saathi
 * asks which one instead of guessing. Same-city duplicates never trigger a question. Pure, so it is JVM-tested.
 */
object PlaceChoice {
    private const val MAX_OPTIONS = 3

    /** One candidate per distinct area, nearest first as the geocoder ranked them; empty when there's nothing to ask. */
    fun options(candidates: List<PlaceCandidate>): List<PlaceCandidate> {
        val perArea = candidates.filter { !it.area.isNullOrBlank() }.distinctBy { it.area!!.trim().lowercase() }
        return if (perArea.size >= 2) perArea.take(MAX_OPTIONS) else emptyList()
    }

    fun question(place: String, options: List<PlaceCandidate>, style: ReplyStyle): String {
        val areas = options.mapNotNull { it.area }
        val list = areas.dropLast(1).joinToString(", ") + tr(style, " or ", " या ", " ya ") + areas.last()
        return tr(style, "I found $place in $list. Which one?", "$place मुझे $list में मिला। कौन सा?",
            "$place mujhe $list mein mila. Kaun sa?")
    }

    private val ORDINALS = listOf(
        Regex("(?i)\\b(first|pehla|pehle|pahla|1st)\\b|पहला|पहले"),
        Regex("(?i)\\b(second|doosra|dusra|doosre|2nd|two)\\b|दूसरा|दूसरे"),
        Regex("(?i)\\b(third|teesra|tisra|3rd|three|teen)\\b|तीसरा"),
    )

    /** The option the answer names (by area, or "the first / doosra"), or null if it's unclear. */
    fun match(answer: String?, options: List<PlaceCandidate>): PlaceCandidate? {
        val a = answer?.lowercase()?.trim().orEmpty()
        if (a.isEmpty()) return null
        options.filter { o -> o.area?.lowercase()?.let { a.contains(it) || it.contains(a) } == true }
            .singleOrNull()?.let { return it }
        val byOrdinal = ORDINALS.indices.filter { ORDINALS[it].containsMatchIn(a) && it < options.size }
        return byOrdinal.singleOrNull()?.let { options[it] }
    }
}
