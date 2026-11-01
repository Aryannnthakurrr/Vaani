package com.saathi.app.core.privacy

/** What kind of private value a span is; decides how it is read aloud. */
enum class PrivateKind { OTP, ACCOUNT, CARD, AADHAAR, PAN, AMOUNT }

data class PrivateSpan(val kind: PrivateKind, val value: String)

/** [text] with every private value replaced by a local slot marker `⟦#n⟧`; [spans]`[n]` holds the value. */
data class Extracted(val text: String, val spans: List<PrivateSpan>)

/**
 * Reversible on-device redaction (SPEC §7.4, extended). Sensitive values become tokens before any text leaves the
 * phone; the values stay in the local vault, and only the phone's own TTS ever speaks them. Pure, JVM-tested.
 *
 * Tokens are `⟦P<id>⟧` (vault row id). Local, not-yet-stored slots are `⟦#<n>⟧` until [bind] swaps in the ids.
 */
object PrivateSpans {
    val TOKEN = Regex("⟦P(\\d+)⟧")
    private val SLOT = Regex("⟦#(\\d+)⟧")

    private val otpKeyword = Regex("(?i)(\\botp\\b|one[ -]?time|verification code|code is|passcode|security code|ओटीपी|सत्यापन कोड)")
    private val otpDigits = Regex("(?<![\\d₹.,P#Xx*/-])\\d{4,8}(?![\\d,/-]|\\.\\d)")
    private val card = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val aadhaar = Regex("(?<!\\d)\\d{4} ?\\d{4} ?\\d{4}(?!\\d)")
    private val pan = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    private val accountKeyword = Regex("(?i)(a/c|acct|account|खाता|khata)")
    private val accountDigits = Regex("(?<![\\dP#])[Xx*]*\\d{4,18}(?!\\d)")
    private val moneyKeyword = Regex("(?i)(debit|credit|spent|paid|received|withdrawn|deposited|transferred|refund|balance|bal\\b|डेबिट|क्रेडिट|जमा|निकाले)")
    private val amount = Regex("(?i)(?:₹|rs\\.?|inr)\\s?(\\d[\\d,]*(?:\\.\\d{1,2})?)")

    fun extract(text: String): Extracted {
        val spans = mutableListOf<PrivateSpan>()
        var out = text
        fun swap(regex: Regex, kind: PrivateKind, region: IntRange? = null, valueOf: (MatchResult) -> String? = { it.value }) {
            val matches = regex.findAll(out).filter { region == null || it.range.first in region }.toList().asReversed()
            for (m in matches) {
                val value = valueOf(m) ?: continue
                if (SLOT.containsMatchIn(m.value)) continue
                spans.add(PrivateSpan(kind, value))
                out = out.replaceRange(m.range, "⟦#${spans.lastIndex}⟧")
            }
        }
        // OTP: digits within 40 characters of an OTP keyword.
        for (kw in otpKeyword.findAll(out).toList().asReversed()) {
            swap(otpDigits, PrivateKind.OTP, maxOf(0, kw.range.first - 40)..(kw.range.last + 40))
        }
        swap(card, PrivateKind.CARD) { m -> m.value.filter(Char::isDigit).takeIf { it.length in 13..19 && Redactor.luhn(it) } }
        swap(aadhaar, PrivateKind.AADHAAR) { it.value.filter(Char::isDigit) }
        swap(pan, PrivateKind.PAN)
        for (kw in accountKeyword.findAll(out).toList().asReversed()) {
            swap(accountDigits, PrivateKind.ACCOUNT, (kw.range.last + 1)..(kw.range.last + 30))
        }
        if (moneyKeyword.containsMatchIn(out)) swap(amount, PrivateKind.AMOUNT) { it.groupValues[1].replace(",", "") }
        // Spans were collected in replacement order; number the slots left to right so `⟦#0⟧` comes first.
        val ordered = SLOT.findAll(out).map { spans[it.groupValues[1].toInt()] }.toList()
        var i = 0
        return Extracted(SLOT.replace(out) { "⟦#${i++}⟧" }, ordered)
    }

    /** The text with every token and slot blanked, so two copies of one message compare equal. */
    fun shape(text: String): String = SLOT.replace(TOKEN.replace(text, "⟦⟧"), "⟦⟧")

    /** Replaces local slots with vault tokens once the values are stored ([ids] in slot order). */
    fun bind(text: String, ids: List<Long>): String {
        var i = 0
        return SLOT.replace(text) { "⟦P${ids[i++]}⟧" }
    }

    /** A cloud-safe sentence split into parts the cloud voice may say and private tokens only the phone may say. */
    sealed interface Part {
        data class Public(val text: String) : Part
        data class Private(val id: Long) : Part
    }

    fun parts(text: String): List<Part> {
        val out = mutableListOf<Part>()
        var last = 0
        for (m in TOKEN.findAll(text)) {
            text.substring(last, m.range.first).takeIf { it.isNotBlank() }?.let { out += Part.Public(it) }
            out += Part.Private(m.groupValues[1].toLong())
            last = m.range.last + 1
        }
        text.substring(last).takeIf { it.isNotBlank() }?.let { out += Part.Public(it) }
        return out
    }

    /** How the phone reads a value: codes digit by digit, money in rupees. Hindi digits for Hindi users. */
    fun spoken(span: PrivateSpan, hindi: Boolean): String = when (span.kind) {
        PrivateKind.AMOUNT -> span.value.removeSuffix(".00") + if (hindi) " रुपये" else " rupees"
        PrivateKind.PAN -> span.value.toCharArray().joinToString(" ")
        else -> span.value.filter(Char::isLetterOrDigit).toCharArray().joinToString(" ")
    }

    /** Spoken name for a value that can't be found any more (deleted message). */
    fun missing(hindi: Boolean) = if (hindi) "वह नंबर अब फ़ोन में नहीं है" else "that number is no longer on the phone"
}
