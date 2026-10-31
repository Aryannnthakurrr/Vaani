package com.saathi.app.features.scam

/** Pulls links out of message text, including bare domains and shorteners. Never opens them. */
object UrlExtractor {
    private const val TLDS = "com|in|net|org|co|info|xyz|top|ly|gl|me|io|app|online|site|club|link|live|shop|vip|" +
        "cc|tk|ml|ga|cf|gq|ru|cn|biz|icu|buzz|gov|sbi|bank|apk|at|id|to|us|ws"
    private val urlRegex = Regex(
        "(?i)(?<![@\\w.])((?:https?://|www\\.)[^\\s<>\"']+|(?:[a-z0-9-]+\\.)+(?:$TLDS)(?![a-z0-9-])(?:[/?#][^\\s<>\"']*)?)",
    )
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "rb.gy", "is.gd", "shorturl.at", "tiny.cc",
        "s.id", "t.ly", "ow.ly", "rebrand.ly", "v.gd", "shorte.st",
    )

    fun extract(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']') }
            .filter { it.length > 4 }.distinct().toList()

    /** Lower-case host without scheme, port, path or a leading "www.". */
    fun host(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val hostPort = noScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return hostPort.substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
    }

    fun path(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val i = noScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (i < 0) "" else noScheme.substring(i).lowercase()
    }

    /** APK rule: anything that ends in .apk or has apk/download in its path is RED immediately. */
    fun isApkLink(url: String): Boolean {
        val p = path(url).substringBefore('#')
        return p.substringBefore('?').endsWith(".apk") || host(url).endsWith(".apk") ||
            p.contains("apk") || p.contains("/download")
    }

    fun isShortener(url: String): Boolean = host(url) in shorteners
}
