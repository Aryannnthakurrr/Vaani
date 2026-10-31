package com.saathi.app.core.net

/**
 * What leaves the phone for a link check: scheme, host and path. The query, fragment, login and token-like path
 * parts (reset tokens, file ids, UUIDs, phone or account numbers) are dropped or become "…"; short-link codes such
 * as bit.ly/3xYz9Ab stay so redirects can still be followed. Twin of the backend's privacy_url
 * (backend/app/sandbox/url_checks.py); both are tested on fixtures/link_privacy/cases.json.
 */
object LinkPrivacy {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX = Regex("^[0-9a-fA-F]{16,}$")
    private val DIGITS = Regex("[0-9]{9,}")

    fun tokenLike(segment: String): Boolean =
        UUID.matches(segment) || HEX.matches(segment) || DIGITS.containsMatchIn(segment) ||
            (segment.length >= 20 && segment.any { it.isDigit() } && segment.any { it.isLetter() })

    fun strip(raw: String): String {
        var text = raw.trim().trim('<', '>', '"', '\'')
        if (!SCHEME.containsMatchIn(text)) text = "http://$text"
        val scheme = text.substringBefore("://").lowercase()
        val rest = text.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@').lowercase().trimEnd('.')
        val rawPath = rest.drop(authority.length).substringBefore('?').substringBefore('#')
        val path = rawPath.split('/').joinToString("/") { if (it.isNotEmpty() && tokenLike(it)) "…" else it }
        return "$scheme://$hostPort" + path.ifEmpty { "/" }
    }
}
