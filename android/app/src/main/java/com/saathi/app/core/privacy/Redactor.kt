package com.saathi.app.core.privacy

data class Redacted(val text: String, val isOtp: Boolean, val otpCode: String? = null)

/**
 * On-device redaction (SPEC §7.4). Runs before anything is stored or sent.
 * OTP messages are replaced entirely; other sensitive numbers are masked in place.
 * ₹ amounts, dates and ordinary phone numbers are left alone.
 */
object Redactor {
    const val OTP_PLACEHOLDER = "[OTP message hidden]"

    private val otpKeyword = Regex(
        "(?i)(\\botp\\b|one[ -]?time|verification code|code is|passcode|security code|ओटीपी|सत्यापन कोड)",
    )
    // "P" and "#": digits inside a vault token (⟦P12⟧) or local slot (⟦#0⟧) are not a code.
    private val otpDigits = Regex("(?<![\\d₹.,P#])\\d{4,8}(?![\\d,]|\\.\\d)")
    private val cardLike = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val aadhaar = Regex("(?<!\\d)\\d{4}[ ]?\\d{4}[ ]?\\d{4}(?!\\d)")
    private val accountKeyword = Regex("(?i)(a/c|acct|account|खाता|khata)")
    private val accountDigits = Regex("(?<![\\dP#])[Xx*]*\\d{9,18}(?!\\d)")
    // A full stop right after the handle ends the sentence ("…to ramesh.k@okaxis."); only ".com"-style domains are email.
    private val upi = Regex("\\b([A-Za-z0-9._-]{2,})@([A-Za-z]{2,})\\b(?![@\\w]|\\.\\w)")
    private val longDigits = Regex("(?<![\\dP#])\\d{11,18}(?!\\d)")

    /** Returns the OTP code when [text] looks like an OTP message, else null. Uses the raw text. */
    fun detectOtp(text: String): String? {
        for (kw in otpKeyword.findAll(text)) {
            val from = maxOf(0, kw.range.first - 40)
            val to = minOf(text.length, kw.range.last + 41)
            val window = text.substring(from, to)
            otpDigits.find(window)?.let { return it.value }
        }
        return null
    }

    fun redact(text: String): Redacted {
        detectOtp(text)?.let { return Redacted(OTP_PLACEHOLDER, isOtp = true, otpCode = it) }
        var out = maskCards(text)
        val masked = mutableSetOf<String>()
        out = maskAccounts(out, masked)
        // The same account number elsewhere in the text (a link's ?acc=, a repeat) goes too, and so does any 11–18
        // digit run: longer than a phone number, so an account, card or reference number.
        for (number in masked) out = out.replace(number, "XX" + number.takeLast(4))
        out = aadhaar.replace(out) { m -> "XXXX XXXX " + m.value.filter(Char::isDigit).takeLast(4) }
        out = longDigits.replace(out) { m -> "XX" + m.value.takeLast(4) }
        out = upi.replace(out) { m -> m.groupValues[1].first() + "***@" + m.groupValues[2] }
        return Redacted(out, isOtp = false)
    }

    private fun maskCards(text: String): String = cardLike.replace(text) { m ->
        val digits = m.value.filter(Char::isDigit)
        if (digits.length in 13..19 && luhn(digits)) "XXXX-XXXX-XXXX-" + digits.takeLast(4) else m.value
    }

    /** Masks 9–18 digit numbers that sit within ~30 chars after an account keyword. */
    private fun maskAccounts(text: String, masked: MutableSet<String> = mutableSetOf()): String {
        var out = text
        for (kw in accountKeyword.findAll(text).toList().asReversed()) {
            val start = kw.range.last + 1
            var end = minOf(out.length, start + 30)
            while (end < out.length && out[end].isDigit()) end++ // never split a number at the window edge
            if (start >= end) continue
            val window = out.substring(start, end)
            val replaced = accountDigits.replace(window) { m ->
                m.value.filter(Char::isDigit).also { masked += it }.let { "XX" + it.takeLast(4) }
            }
            out = out.substring(0, start) + replaced + out.substring(end)
        }
        return out
    }

    fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
