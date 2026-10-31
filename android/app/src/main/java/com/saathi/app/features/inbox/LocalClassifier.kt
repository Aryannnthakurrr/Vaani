package com.saathi.app.features.inbox

import com.saathi.app.core.net.Appointment
import com.saathi.app.core.net.ClassifyItem
import com.saathi.app.core.net.ClassifyResult
import com.saathi.app.features.scam.ScamHeuristics
import com.saathi.app.features.scam.UrlExtractor
import com.saathi.app.features.scam.UssdDetector
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device message classifier: keyword + regex rules in English and Hindi. Used for messages from
 * saved contacts (which never leave the phone) and as the fallback when the backend is unreachable.
 */
object LocalClassifier {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val paymentDone = r("debited|credited|paid successfully|payment (of .{0,20})?(is )?(received|successful)|txn .{0,20}success|भुगतान सफल|जमा")
    private val appointment = r("appointment|\\bdr\\.|doctor|clinic|hospital visit|अपॉइंटमेंट|डॉक्टर")
    private val delivery = r("out for delivery|delivered|shipped|dispatched|arriving|will be delivered|order .{0,30}(on the way|packed)|डिलीवरी")
    private val renewal = r("renew|subscription|membership|auto-?debit|auto-?pay|recharge (is )?due|plan expires|नवीनीकरण")
    private val bill = r("\\bbill\\b|due (on|date|by)|is due|pay by|last date|outstanding|amount due|बिल|देय")
    private val promo = r("offer|sale|discount|% off|cashback|coupon|deal of|free gift|shop now|ऑफर|छूट")

    private val amountRegex = r("(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)")
    private val dmy = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
    private val dayMonth = r("\\b(\\d{1,2})(?:st|nd|rd|th)?[ -]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?(?:[ ,-]+(\\d{4}))?")
    private val monthDay = r("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? (\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val merchants = listOf(
        "BSES", "Tata Power", "Adani", "BESCOM", "MSEDCL", "Airtel", "Jio", "Vi", "BSNL", "Netflix", "Hotstar",
        "Prime Video", "Spotify", "YouTube", "Amazon", "Flipkart", "Swiggy", "Zomato", "LIC", "HDFC", "SBI",
        "ICICI", "Axis", "Kotak", "Paytm", "PhonePe", "Apollo", "Practo", "Indane", "HP Gas", "Bharat Gas",
        "Delhivery", "Blue Dart", "India Post", "Mahanagar Gas", "IGL", "Tata Play",
    )

    fun classify(item: ClassifyItem): ClassifyResult = classify(item, LocalDate.now())

    fun classify(item: ClassifyItem, today: LocalDate): ClassifyResult {
        val t = item.text
        val scam = UssdDetector.detect(t, fromContact = false)?.reason == "ussd_forwarding" ||
            UrlExtractor.extract(t).any(UrlExtractor::isApkLink) || ScamHeuristics.reasons(t).isNotEmpty()
        val category = when {
            scam -> "scam_suspected"
            paymentDone.containsMatchIn(t) -> "payment_done"
            appointment.containsMatchIn(t) -> "appointment"
            delivery.containsMatchIn(t) -> "delivery"
            renewal.containsMatchIn(t) -> "subscription_renewal"
            bill.containsMatchIn(t) -> "bill_due"
            promo.containsMatchIn(t) -> "promo"
            else -> "other"
        }
        val amount = amount(t)
        val due = date(t, today)
        val merchant = merchants.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) }
            ?: item.sender.takeIf { it.any(Char::isLetter) }?.substringAfter('-')
        return ClassifyResult(item.id, category, amount, due?.toString(), merchant, summary(category, merchant, amount, due, t))
    }

    fun amount(text: String): Double? =
        amountRegex.find(text)?.groupValues?.get(1)?.replace(",", "")?.trimEnd('.')?.toDoubleOrNull()

    fun date(text: String, today: LocalDate): LocalDate? {
        dmy.find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            runCatching { return LocalDate.of(year, mo.toInt(), d.toInt()) }
        }
        dayMonth.find(text)?.let { m ->
            val month = months.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            val year = m.groupValues[3].toIntOrNull() ?: today.year
            runCatching { return rollForward(LocalDate.of(year, month, m.groupValues[1].toInt()), today, m.groupValues[3].isEmpty()) }
        }
        monthDay.find(text)?.let { m ->
            val month = months.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            runCatching { return rollForward(LocalDate.of(today.year, month, m.groupValues[2].toInt()), today, true) }
        }
        val lower = text.lowercase()
        return when {
            Regex("\\b(tomorrow|kal)\\b").containsMatchIn(lower) || lower.contains("कल") -> today.plusDays(1)
            Regex("\\b(today|tonight|aaj)\\b").containsMatchIn(lower) || lower.contains("आज") -> today
            else -> null
        }
    }

    private fun rollForward(d: LocalDate, today: LocalDate, yearGuessed: Boolean) =
        if (yearGuessed && d.isBefore(today.minusMonths(2))) d.plusYears(1) else d

    private fun summary(category: String, merchant: String?, amount: Double?, due: LocalDate?, text: String): String {
        val amt = amount?.let { "₹" + formatRupees(it) }
        val label = when (category) {
            "bill_due" -> "Bill"
            "subscription_renewal" -> "Renewal"
            "appointment" -> "Appointment"
            "delivery" -> "Delivery"
            "payment_done" -> "Payment done"
            "scam_suspected" -> "Suspicious message"
            "promo" -> "Offer"
            else -> null
        }
        if (label == null) return text.take(90)
        return listOfNotNull(label, merchant, amt, due?.let { "on " + it.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) })
            .joinToString(" · ")
    }

    /** Indian digit grouping without decimals when whole: 1240 → "1,240", 125000 → "1,25,000". */
    fun formatRupees(v: Double): String {
        val whole = v.toLong()
        val frac = if (v != whole.toDouble()) String.format(Locale.ENGLISH, "%.2f", v).substringAfter('.') else null
        val s = whole.toString()
        val grouped = if (s.length <= 3) s else {
            val head = s.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
        }
        return if (frac != null) "$grouped.$frac" else grouped
    }

    // ------------------------------------------------------------------ appointment extraction

    private val quoted = Regex("\"([^\"]+)\"")
    private val doctorRegex = Regex("Dr\\.?\\s+[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?")
    private val phoneRegex = Regex("\\+?\\d[\\d -]{8,}\\d")
    private val timeRegex = Regex("(?i)(today|tomorrow|\\d{1,2} [A-Za-z]{3,9}(?: \\d{4})?)\\s*,?\\s*(\\d{1,2}(?::\\d{2})?\\s*[AP]M)")
    private val feeRegex = Regex("₹\\s?[0-9][0-9,]*")

    fun extractAppointment(screenText: String): Appointment =
        extractAppointment(screenText, LocalDate.now(), ZoneId.systemDefault())

    /** Pulls doctor, clinic, phone and start time from a booking confirmation screen (serialized tree or plain text). */
    fun extractAppointment(screenText: String, today: LocalDate, zone: ZoneId): Appointment {
        val strings = quoted.findAll(screenText).map { it.groupValues[1] }.toList().ifEmpty { screenText.lines() }
        val all = strings.joinToString("\n")
        val doctor = doctorRegex.find(all)?.value
        val clinicLine = strings.firstOrNull { Regex("(?i)clinic|hospital|centre|center").containsMatchIn(it) &&
            !it.contains("phone", true) && (doctor == null || !it.startsWith(doctor)) }
        val clinic = clinicLine?.substringBefore(',')?.trim()
        val address = clinicLine?.substringAfter(',', "")?.trim()?.ifEmpty { null }
        val phone = strings.firstOrNull { it.contains("phone", true) }?.let { phoneRegex.find(it)?.value }
            ?: phoneRegex.find(all)?.value
        val start = timeRegex.find(all)?.let { m -> parseStart(m.groupValues[1], m.groupValues[2], today)?.atZone(zone) }
        return Appointment(
            doctor = doctor, clinic = clinic, address = address, phone = phone?.trim(),
            startIso = start?.toOffsetDateTime()?.toString(), fee = feeRegex.find(all)?.value?.replace(" ", ""),
        )
    }

    private fun parseStart(day: String, time: String, today: LocalDate): LocalDateTime? {
        val date = when {
            day.equals("today", true) -> today
            day.equals("tomorrow", true) -> today.plusDays(1)
            else -> date(day, today)
        } ?: return null
        val tm = Regex("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*([AP])M").find(time) ?: return null
        var h = tm.groupValues[1].toInt() % 12
        if (tm.groupValues[3].equals("P", true)) h += 12
        return LocalDateTime.of(date, LocalTime.of(h, tm.groupValues[2].ifEmpty { "0" }.toInt()))
    }
}
