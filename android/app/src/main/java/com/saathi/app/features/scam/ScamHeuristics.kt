package com.saathi.app.features.scam

/**
 * Bilingual (EN / HI / Hinglish) text patterns of common Indian scams (SPEC §7.6 F1).
 * Each hit returns a reason code; [ReasonText] turns codes into plain language.
 */
object ScamHeuristics {
    private data class Rule(val code: String, val all: List<Regex>)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val rules = listOf(
        Rule("kyc", listOf(r("kyc|केवाईसी|pan card|aadhaar link"), r("expir|update|block|suspend|pending|band|बंद|अपडेट|verify|link"))),
        Rule("account_blocked", listOf(r("(account|a/c|card|sim|khata|खाता|खाते).{0,40}(block|suspend|deactivat|band ho|बंद|freeze|frozen)"))),
        Rule("electricity", listOf(r("electricity|bijli|बिजली|power supply"), r("disconnect|cut|kaat|kat jayegi|कट|कटेगी|tonight|aaj raat|आज रात"))),
        Rule("customs", listOf(r("customs|parcel.{0,30}(held|hold|seized)|courier.{0,30}(hold|held|pending|return)|पार्सल"))),
        Rule("lottery", listOf(r("lottery|jackpot|\\bkbc\\b|you (have )?won|prize|lucky draw|इनाम|लॉटरी|inaam|jeeta"))),
        Rule("police", listOf(r("\\bcbi\\b|digital arrest|narcotics|police|\\bed officer|cyber cell|arrest warrant|पुलिस|गिरफ्तार|giraftar|trai"))),
        Rule("refund_upi", listOf(r("refund|cashback|wapas|वापस"), r("upi|collect request|enter (your )?pin|pin daal|पिन"))),
        Rule("qr_receive", listOf(r("scan"), r("\\bqr\\b|क्यूआर"), r("receive|get|milega|मिलेगा|paane"))),
        Rule("urgent_pay", listOf(r("(pay|transfer|bhej|भेज).{0,30}(immediately|urgent|within \\d+ (min|hour)|turant|तुरंत)"))),
    )

    fun reasons(text: String): List<String> =
        rules.filter { rule -> rule.all.all { it.containsMatchIn(text) } }.map { it.code }
}
