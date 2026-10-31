package com.saathi.app.features.scam

import com.saathi.app.core.net.LinkVerdict

/**
 * Offline link checks (SPEC §7.6 F2 "cheap checks"): APK, IP host, punycode, risky TLDs, shorteners,
 * and brand lookalikes with homoglyph folding + edit distance. The backend adds expansion, WHOIS age,
 * Safe Browsing and the sandbox; this is the fallback when it is unreachable.
 */
object LocalLinkRules {
    val BRANDS = listOf(
        "sbi", "onlinesbi", "yonosbi", "hdfc", "hdfcbank", "icici", "icicibank", "axis", "axisbank", "kotak",
        "paytm", "phonepe", "gpay", "amazon", "flipkart", "irctc", "uidai", "aadhaar", "incometax", "indiapost",
        "bluedart", "delhivery", "npci", "bescom", "tatapower", "msedcl", "bsesdelhi", "adanielectricity", "epfo",
    )
    private val official = listOf(
        "sbi.co.in", "onlinesbi.sbi", "onlinesbi.com", "sbi.bank.in", "hdfcbank.com", "icicibank.com",
        "axisbank.com", "kotak.com", "paytm.com", "phonepe.com", "pay.google.com", "amazon.in", "amazon.com",
        "flipkart.com", "irctc.co.in", "uidai.gov.in", "incometax.gov.in", "indiapost.gov.in", "bluedart.com",
        "delhivery.com", "npci.org.in", "bescom.co.in", "tatapower.com", "mahadiscom.in", "bsesdelhi.com",
        "adanielectricity.com", "epfindia.gov.in", "google.com", "youtube.com", "whatsapp.com", "gov.in",
    )
    private val suffixes = listOf("co.in", "gov.in", "org.in", "net.in", "ac.in", "bank.in", "co.uk", "com.au")
    private val riskyTlds = setOf("xyz", "top", "tk", "ml", "ga", "cf", "gq", "icu", "buzz", "vip", "club", "online", "site", "live", "shop")
    private val lureWords = setOf("kyc", "update", "verify", "login", "reward", "refund", "offer", "bonus", "secure",
        "account", "block", "support", "help", "gift", "prize", "pay", "points", "redeem")
    private val ipHost = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isOfficial(host: String): Boolean = official.any { host == it || host.endsWith(".$it") }

    /** Folds look-alike characters so "sbl", "5bi" and "rnobile" compare equal to their brand spelling. */
    fun fold(s: String): String = s.lowercase()
        .replace("rn", "m").replace("vv", "w")
        .map { c ->
            when (c) {
                '0' -> 'o'; '1', 'i', '|', '!' -> 'l'; '3' -> 'e'; '4', '@' -> 'a'; '5', '$' -> 's'; '7' -> 't'; '8' -> 'b'
                'а' -> 'a'; 'е' -> 'e'; 'о' -> 'o'; 'р' -> 'p'; 'с' -> 'c'; 'х' -> 'x'; 'і' -> 'l' // Cyrillic homoglyphs
                else -> c
            }
        }.joinToString("")

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Labels of the host without its public suffix, e.g. "secure-sbi.kyc.co.in" → ["secure-sbi", "kyc"]. */
    private fun labels(host: String): List<String> {
        val suffix = suffixes.firstOrNull { host.endsWith(".$it") }
        val trimmed = if (suffix != null) host.removeSuffix(".$suffix") else host.substringBeforeLast('.', host)
        return trimmed.split('.').filter { it.isNotEmpty() }
    }

    fun signals(url: String): List<Pair<String, ScamLevel>> {
        val host = UrlExtractor.host(url)
        val out = mutableListOf<Pair<String, ScamLevel>>()
        if (UrlExtractor.isApkLink(url)) out += "apk_link" to ScamLevel.RED
        if (ipHost.matches(host)) out += "ip_host" to ScamLevel.AMBER
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) out += "punycode" to ScamLevel.AMBER
        if (UrlExtractor.isShortener(url)) out += "shortener" to ScamLevel.AMBER
        if (host.substringAfterLast('.') in riskyTlds) out += "risky_tld" to ScamLevel.AMBER
        if (!isOfficial(host)) out += brandSignals(host)
        return out
    }

    /** Short brands only match through homoglyphs; longer ones also tolerate typos. */
    private fun typoLimit(brand: String) = when {
        brand.length <= 4 -> 0
        brand.length <= 6 -> 1
        else -> 2
    }

    /** Exact brand on a foreign domain, then homoglyph/typo lookalikes, then brand-as-substring. */
    private fun brandSignals(host: String): List<Pair<String, ScamLevel>> {
        val tokens = labels(host).flatMap { it.split('-', '_') }.filter { it.length >= 3 }
        val hasLure = tokens.any { t -> lureWords.any { t.contains(it) } }
        val unofficial = if (hasLure) ScamLevel.RED else ScamLevel.AMBER
        for (t in tokens) {
            BRANDS.firstOrNull { it == t }?.let { return listOf("brand_unofficial:$it" to unofficial) }
            val folded = fold(t)
            BRANDS.firstOrNull { b -> levenshtein(folded, fold(b)) <= typoLimit(b) }
                ?.let { return listOf("lookalike:$it" to ScamLevel.RED) }
        }
        for (t in tokens) {
            BRANDS.firstOrNull { b -> b.length >= 4 && t.contains(b) }
                ?.let { return listOf("brand_unofficial:$it" to unofficial) }
        }
        return emptyList()
    }

    fun verdict(url: String): LinkVerdict {
        val sig = signals(url)
        val level = sig.fold(ScamLevel.NONE) { acc, s -> acc.max(s.second) }
        val verdict = when (level) {
            ScamLevel.RED -> "dangerous"
            ScamLevel.AMBER -> "suspicious"
            else -> "safe"
        }
        val (en, hi) = when {
            sig.any { it.first == "apk_link" } -> "This link installs an app. It can steal your money." to
                "यह लिंक एक ऐप डाउनलोड करता है। यह आपके पैसे चुरा सकता है।"
            sig.any { it.first.startsWith("lookalike") || it.first.startsWith("brand_unofficial") } ->
                "This link pretends to be a bank or shop, but it is a fake website." to
                    "यह लिंक बैंक या दुकान जैसा दिखता है, पर यह नकली वेबसाइट है।"
            level == ScamLevel.AMBER -> "This link looks unusual. Please do not open it." to
                "यह लिंक अजीब लग रहा है। कृपया इसे मत खोलिए।"
            else -> "No problem found with this link." to "इस लिंक में कोई खतरा नहीं मिला।"
        }
        return LinkVerdict(url = url, finalUrl = url, verdict = verdict, reasonSimple = en, reasonSimpleHi = hi,
            signals = sig.map { it.first })
    }
}
