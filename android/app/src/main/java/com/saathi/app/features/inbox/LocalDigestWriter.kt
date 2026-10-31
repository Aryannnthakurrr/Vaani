package com.saathi.app.features.inbox

import com.saathi.app.core.net.DigestItem
import com.saathi.app.core.net.ReplyStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Template digest in EN / HI / Hinglish, used offline and by the fake backend. Promos are skipped. */
object LocalDigestWriter {

    fun write(items: List<DigestItem>, style: ReplyStyle, userName: String?): String =
        write(items, style, userName, LocalDate.now())

    fun write(items: List<DigestItem>, style: ReplyStyle, userName: String?, today: LocalDate): String {
        val name = userName?.trim()?.takeIf { it.isNotEmpty() }
        val greeting = when (style) {
            ReplyStyle.EN -> "Good morning" + (name?.let { ", $it" } ?: "") + "."
            ReplyStyle.HI -> "सुप्रभात" + (name?.let { " $it जी" } ?: "") + "।"
            ReplyStyle.HINGLISH -> "Good morning" + (name?.let { " $it ji" } ?: "") + "."
        }
        val useful = items.filter { it.category !in setOf("promo", "personal") }
        val lines = useful.filter { it.category != "scam_suspected" }
            .sortedBy { it.dueDate ?: "9999" }
            .mapNotNull { line(it, style, today) }
            .distinct()
        val scamCount = useful.count { it.category == "scam_suspected" }
        val out = mutableListOf(greeting)
        if (lines.isEmpty() && scamCount == 0) {
            out += when (style) {
                ReplyStyle.EN -> "Nothing new today. Have a nice day."
                ReplyStyle.HI -> "आज कुछ नया नहीं है। आपका दिन अच्छा हो।"
                ReplyStyle.HINGLISH -> "Aaj kuch naya nahi hai. Aapka din achha ho."
            }
        }
        out += lines
        if (scamCount > 0) {
            out += when (style) {
                ReplyStyle.EN -> "Warning: $scamCount ${if (scamCount == 1) "message looks" else "messages look"} like a scam. Do not reply or share any code."
                ReplyStyle.HI -> "सावधान: $scamCount संदेश धोखे जैसे लग रहे हैं। उनका जवाब मत दीजिए और कोई कोड मत बताइए।"
                ReplyStyle.HINGLISH -> "Savdhaan: $scamCount message dhokhe jaise lag rahe hain. Unka jawab mat dijiye aur koi code mat bataiye."
            }
        }
        return out.joinToString(" ")
    }

    private fun line(it: DigestItem, style: ReplyStyle, today: LocalDate): String? {
        val who = it.merchant?.trim()?.takeIf { m -> m.isNotEmpty() }
        val amt = it.amount?.let { a -> "₹" + LocalClassifier.formatRupees(a) }
        val due = it.dueDate?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
        val whenText = due?.let { d -> relative(d, today, style) }
        return when (it.category) {
            "bill_due" -> when (style) {
                ReplyStyle.EN -> "Your ${who?.let { w -> "$w " } ?: ""}bill${amt?.let { a -> " of $a" } ?: ""} is due${whenText?.let { w -> " $w" } ?: " soon"}."
                ReplyStyle.HI -> "आपका ${who?.let { w -> "$w का " } ?: ""}${amt?.let { a -> "$a का " } ?: ""}बिल ${whenText?.let { w -> "$w तक" } ?: "जल्दी"} भरना है।"
                ReplyStyle.HINGLISH -> "Aapka ${who?.let { w -> "$w ka " } ?: ""}${amt?.let { a -> "$a ka " } ?: ""}bill ${whenText?.let { w -> "$w tak" } ?: "jaldi"} bharna hai."
            }
            "subscription_renewal" -> when (style) {
                ReplyStyle.EN -> "${who ?: "A subscription"} renews${whenText?.let { w -> " $w" } ?: ""}${amt?.let { a -> " for $a" } ?: ""}."
                ReplyStyle.HI -> "${who ?: "एक सब्सक्रिप्शन"} ${whenText ?: "जल्दी"} ${amt?.let { a -> "$a में " } ?: ""}रिन्यू होगा।"
                ReplyStyle.HINGLISH -> "${who ?: "Ek subscription"} ${whenText ?: "jaldi"} ${amt?.let { a -> "$a mein " } ?: ""}renew hoga."
            }
            "appointment" -> when (style) {
                ReplyStyle.EN -> "You have an appointment${who?.let { w -> " with $w" } ?: ""}${whenText?.let { w -> " $w" } ?: ""}."
                ReplyStyle.HI -> "${whenText?.let { w -> "$w " } ?: ""}आपकी ${who?.let { w -> "$w के साथ " } ?: ""}अपॉइंटमेंट है।"
                ReplyStyle.HINGLISH -> "${whenText?.let { w -> "$w " } ?: ""}aapki ${who?.let { w -> "$w ke saath " } ?: ""}appointment hai."
            }
            "delivery" -> when (style) {
                ReplyStyle.EN -> "A delivery${who?.let { w -> " from $w" } ?: ""} is on the way."
                ReplyStyle.HI -> "${who?.let { w -> "$w से " } ?: ""}एक डिलीवरी आ रही है।"
                ReplyStyle.HINGLISH -> "${who?.let { w -> "$w se " } ?: ""}ek delivery aa rahi hai."
            }
            "payment_done" -> when (style) {
                ReplyStyle.EN -> "A payment${amt?.let { a -> " of $a" } ?: ""}${who?.let { w -> " to $w" } ?: ""} went through."
                ReplyStyle.HI -> "${amt?.let { a -> "$a का " } ?: ""}एक भुगतान हो गया है।"
                ReplyStyle.HINGLISH -> "${amt?.let { a -> "$a ka " } ?: ""}ek payment ho gaya hai."
            }
            else -> it.summary.takeIf { s -> s.isNotBlank() }?.let { s -> if (s.endsWith('.')) s else "$s." }
        }?.replace("  ", " ")?.trim()?.replaceFirstChar { c -> c.uppercase() }
    }

    fun relative(d: LocalDate, today: LocalDate, style: ReplyStyle): String {
        val days = ChronoUnit.DAYS.between(today, d)
        return when (style) {
            ReplyStyle.EN -> when {
                days == 0L -> "today"
                days == 1L -> "tomorrow"
                days in 2..6 -> "on " + d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                else -> "on " + d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
            }
            ReplyStyle.HI -> when {
                days == 0L -> "आज"
                days == 1L -> "कल"
                days in 2..6 -> hindiDay(d.dayOfWeek) + " को"
                else -> "${d.dayOfMonth} " + hindiMonth(d.monthValue) + " को"
            }
            ReplyStyle.HINGLISH -> when {
                days == 0L -> "aaj"
                days == 1L -> "kal"
                days in 2..6 -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " ko"
                else -> d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)) + " ko"
            }
        }
    }

    private fun hindiDay(d: DayOfWeek) = listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")[d.ordinal]

    private fun hindiMonth(m: Int) = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त",
        "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")[m - 1]
}
