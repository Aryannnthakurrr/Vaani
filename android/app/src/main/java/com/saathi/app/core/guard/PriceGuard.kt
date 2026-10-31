package com.saathi.app.core.guard

/** One line of text read from a screenshot (OCR), with its box in screen pixels. */
data class TextBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
}

enum class RideClass { CAR, AUTO, BIKE, UNKNOWN }

/** One ride option on screen: its name, type and lowest price shown on its row. */
data class RideOption(val name: String, val rideClass: RideClass, val price: Int, val top: Int)

sealed interface PriceVerdict {
    /** The agreed price is the cheapest of the wanted type (or within a rupee or two of it). */
    data class Ok(val chosen: RideOption?) : PriceVerdict

    /** Something of the same type is cheaper than what is about to be booked. */
    data class CheaperSameType(val quoted: Int, val cheapest: RideOption) : PriceVerdict

    /** The agreed option is fine for its type, but another type is cheaper right now ("cab is cheaper than auto"). */
    data class CheaperOtherType(val quoted: Int, val other: RideOption) : PriceVerdict

    /** No prices could be read; the check says nothing and the booking goes ahead as agreed. */
    data object Unread : PriceVerdict
}

/**
 * The deterministic price check before a booking tap: is the price the user agreed to really the cheapest ride of
 * the type they asked for? Works only on OCR text and boxes, so the same rules run on the phone or on the server
 * (backend/app/guard/price_guard.py is a line-for-line twin; fixtures/price_guard/cases.json drives both tests).
 */
object PriceGuard {
    /** Prices within this many rupees count as the same (surge rounding, ₹1 differences). */
    const val TOLERANCE = 2

    private val PRICE = Regex("(?:₹|Rs\\.?|INR)\\s?(\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)
    private val CAR = Regex("(?i)\\b(go|sedan|premier|xl|mini|prime|cab|comfort|uberx|uber x|suv|hatchback|car)\\b")
    private val AUTO = Regex("(?i)\\b(auto|rickshaw|tuk)\\b")
    private val BIKE = Regex("(?i)\\b(moto|bike|motorcycle|scooter)\\b")

    fun classOf(name: String): RideClass = when {
        AUTO.containsMatchIn(name) -> RideClass.AUTO
        BIKE.containsMatchIn(name) -> RideClass.BIKE
        CAR.containsMatchIn(name) -> RideClass.CAR
        else -> RideClass.UNKNOWN
    }

    fun price(text: String): Int? = PRICE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /** Ride rows: each price joined with the named option on the same row; a row's lowest price wins. */
    fun options(lines: List<TextBox>): List<RideOption> {
        val prices = lines.mapNotNull { l -> price(l.text)?.let { l to it } }
        val names = lines.filter { price(it.text) == null && classOf(it.text) != RideClass.UNKNOWN }
        return prices.mapNotNull { (box, amount) ->
            val half = maxOf(box.bottom - box.top, 40)
            val name = names.filter { kotlin.math.abs(it.centerY - box.centerY) <= half }
                .minByOrNull { kotlin.math.abs(it.centerY - box.centerY) } ?: return@mapNotNull null
            RideOption(name.text.trim(), classOf(name.text), amount, name.top)
        }.groupBy { it.name to it.top }.map { (_, same) -> same.minBy { it.price } }.sortedBy { it.top }
    }

    /**
     * [summary] is what the user agreed to ("Book Uber Go for ₹180?"); [wanted] is the type they asked for. The
     * quoted price is the one in the summary, else the price on the named option's row.
     */
    fun check(lines: List<TextBox>, wanted: RideClass, summary: String): PriceVerdict {
        val all = options(lines)
        if (all.isEmpty()) return PriceVerdict.Unread
        val named = all.firstOrNull { summary.contains(it.name, ignoreCase = true) }
        val quoted = price(summary) ?: named?.price ?: return PriceVerdict.Unread
        val type = if (wanted == RideClass.UNKNOWN) named?.rideClass ?: RideClass.CAR else wanted
        val cheapestOfType = all.filter { it.rideClass == type }.minByOrNull { it.price }
        if (cheapestOfType != null && quoted > cheapestOfType.price + TOLERANCE) {
            return PriceVerdict.CheaperSameType(quoted, cheapestOfType)
        }
        // A cheaper type is only worth mentioning for autos and bikes: a car is the comfortable choice.
        val cheaperOther = all.filter { it.rideClass == RideClass.CAR && type != RideClass.CAR }.minByOrNull { it.price }
        if (cheaperOther != null && cheaperOther.price + TOLERANCE < quoted) {
            return PriceVerdict.CheaperOtherType(quoted, cheaperOther)
        }
        return PriceVerdict.Ok(named ?: cheapestOfType)
    }
}
