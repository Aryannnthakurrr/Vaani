package com.saathi.app.features.tasks

import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.net.Slots
import com.saathi.app.core.voice.tr
import com.saathi.app.util.Packages

/** A slot the user must fill before a task starts, with the question asked when it is missing. */
data class SlotQuestion(val name: String, val ask: (ReplyStyle) -> String, val isFilled: (Slots) -> Boolean, val fill: (Slots, String) -> Slots)

/**
 * One action task (SPEC §7.1): target app, optional deep-link builder, a prompt addendum with known
 * landmarks and where to stop, required slots, and the replay scenario used in mock/demo mode.
 */
data class TaskSpec(
    val id: String,
    val pkg: String,
    val scenario: String,
    val required: List<SlotQuestion>,
    val goal: (Slots) -> String,
    val addendum: String,
    /** Builds the launch link, or null to open the app instead. May look up places near the user. */
    val deeplink: (suspend (Slots, PlaceLookup) -> String?)? = null,
)

/** Deep links built by Saathi's own code. These are the only URIs the agent may open. */
/**
 * "2026-11-01" → "tomorrow (Sun 1 Nov)": Pronto's date chips say "Today", "Tomorrow", "Sun 1", so an ISO date made the
 * agent scroll past the right chip.
 */
object DayWords {
    fun of(date: String, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val d = runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull() ?: return date
        val label = d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
        return when (d) {
            today -> "today ($label)"
            today.plusDays(1) -> "tomorrow ($label)"
            else -> label
        }
    }
}

object DeepLinks {
    /**
     * Uber universal deep link. Uber needs coordinates for the drop-off; without them we open the app and let
     * the agent type the destination instead.
     */
    fun uber(destination: String, lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        return uberString(destination, lat, lng)
    }

    /**
     * "यहाँ से India Gate" / "from here to India Gate" → "India Gate": the pickup words would make the geocoder miss the
     * place, and then there is no deep link.
     */
    fun cleanDestination(said: String): String? {
        val pickup = Regex("""(?i)^(?:from\s+)?(?:here|yahan|yaha|yahaan|यहाँ|यहां|यहा)\s*(?:se|से|to|tak|तक)?\s+|^(?:to|tak)\s+""")
        return said.trim().replace(pickup, "").trim().ifBlank { null }
    }

    /** Pure builder (no android.net.Uri), so JVM tests check the exact link the app opens. */
    fun uberString(destination: String, lat: Double, lng: Double): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "uber://?action=setPickup&pickup=my_location" +
            "&${enc("dropoff[latitude]")}=$lat&${enc("dropoff[longitude]")}=$lng&${enc("dropoff[nickname]")}=${enc(destination)}"
    }
}

object Tasks {
    private val destination = SlotQuestion("destination",
        { tr(it, "Where do you want to go?", "आपको कहाँ जाना है?", "Aapko kahan jaana hai?") },
        { !it.destination.isNullOrBlank() }, { s, v -> s.copy(destination = v) })
    private val product = SlotQuestion("product",
        { tr(it, "What should I order?", "क्या मंगाना है?", "Kya mangwana hai?") },
        { !it.product.isNullOrBlank() }, { s, v -> s.copy(product = v) })
    private val specialty = SlotQuestion("specialty",
        { tr(it, "Which doctor do you need? For example, heart doctor or skin doctor.", "किस डॉक्टर की ज़रूरत है? जैसे दिल के डॉक्टर या त्वचा के डॉक्टर।",
            "Kaunse doctor chahiye? Jaise heart doctor ya skin doctor.") },
        { !it.specialty.isNullOrBlank() || !it.doctor.isNullOrBlank() }, { s, v -> s.copy(specialty = v) })

    val UBER = TaskSpec(
        id = "book_cab", pkg = Packages.UBER, scenario = "uber_book", required = listOf(destination),
        // "Cab" means a car: the cheapest Uber option is usually Moto (a bike), which users asking for a cab don't want.
        // An auto or a bike only when they asked for one.
        goal = {
            when (it.vehicle) {
                "auto" -> "Book an Uber Auto (auto-rickshaw) to ${it.destination}."
                "bike" -> "Book an Uber Moto (bike) to ${it.destination}."
                "xl" -> "Book an Uber XL (a big car with room for a wheelchair) to ${it.destination}."
                else -> "Book the cheapest Uber car (not a bike or auto) to ${it.destination}."
            }
        },
        addendum = "Landmarks: 'Where to?' search box, ride list with fares, a 'Confirm …' button. " +
            "If Uber opens straight on the ride list, the drop-off was set by a deep link: check it matches the goal " +
            "(search again if not), then pick the ride. " +
            "Pick only the ride type the goal names. For a car: Uber Go, Go Sedan, Premier, UberXL or similar; ignore Moto, " +
            "Bike and Auto rows completely (don't read them out, compare them or tap them), and if the selected row is a " +
            "bike or auto, tap the cheapest car row straight away. For an auto: the Auto row. For a bike: the Moto row. " +
            "The ride list is a sheet over the map, cheapest first (Bike, Auto) with the cars BELOW them: if the row you " +
            "need isn't visible, scroll DOWN on the ride list (pass its id), never up; the first scroll pulls the sheet " +
            "up, the next ones move the list. Rows may be missing from the element list: read them from the screenshot. " +
            "If the asked type isn't offered, say so with ask_user instead of picking another. " +
            "Stop after the ride is requested and a driver/vehicle/'arriving' line is visible; read driver, car and ETA.",
        deeplink = { slots, places ->
            slots.destination?.let(DeepLinks::cleanDestination)?.let { d -> places.find(d)?.let { DeepLinks.uber(d, it.lat, it.lng) } }
        },
    )
    val HOUSE_HELP = TaskSpec(
        id = "book_house_help", pkg = Packages.HOUSE_HELP, scenario = "house_help", required = emptyList(),
        goal = {
            "Add a Pronto house-help visit to the cart${it.service?.let { s -> " for $s" } ?: ""}" +
                "${it.date?.let { d -> ", ${DayWords.of(d)}" } ?: ""}${it.time?.let { t -> " at $t" } ?: ""}."
        },
        // Landmarks seen in Pronto on a real phone (2026-10-31 and 11-01): home has Instant / Schedule for Later at the
        // top and an "All house help services" list below the fold; the slot screen shows few slots per tab.
        addendum = "Close any promo pop-up with its close (×) button. " +
            "If the goal names a chore (washroom/bathroom/toilet, kitchen, dusting, utensils, laundry…), scroll down on the " +
            "home screen to 'All house help services' and open the matching service; scroll again if it is not listed yet. " +
            "On a service page (e.g. 'Bathroom Cleaning') each duration card has its own 'Add' button. If the user did not say " +
            "how long, first ask_user with the durations and prices shown (\"30 mins ₹40, 60 mins ₹79 or 90 mins ₹119?\"). " +
            "Tap that card's 'Add' ONCE: it turns 'Added' and a green 'Go to cart' bar appears at the bottom. Pronto does not " +
            "report either to accessibility, so check the screenshot and tap 'Go to cart' with tap_xy; tapping 'Add' again " +
            "removes the service. In the cart, pick the asked day and time slot the same way as below. " +
            "With no chore named, use 'Get Instant Service' when no day or time was asked, else 'Schedule for Later'. " +
            "On the schedule screen pick the asked day (else the earliest), the 'Service duration' the user asked for (if they " +
            "didn't, ask_user with the durations and prices shown), then the tab for the time: Morning before 12 pm, Afternoon 12–5 pm, Evening after 5 pm. " +
            "Tap the slot button that matches the asked time exactly. If it is not among the slots shown, tap the small " +
            "arrow button right of 'Standard slots' to see every slot, and scroll the slots (scroll with that list's id). " +
            "If the asked time is still not offered that day, never fail and never pick another time yourself: ask_user " +
            "with the two nearest times that are offered (or another day). Read day, time, duration and price, and wrap " +
            "'Add to cart' in confirm. Once it is in the cart, finish with done and tell the user to pay in Pronto " +
            "themselves; never open checkout or payment.",
    )
    val SHOP = TaskSpec(
        id = "shop_online", pkg = Packages.AMAZON, scenario = "amazon_add_cart", required = listOf(product),
        goal = { "Add ${it.quantity ?: 1} × ${it.product} to the Amazon cart." },
        addendum = "Search, open the top non-sponsored result matching the name, read price and rating, " +
            "wrap 'Add to Cart' in confirm. Never proceed to checkout or Buy Now.",
    )
    val PRACTO = TaskSpec(
        id = "book_doctor", pkg = Packages.PRACTO, scenario = "practo_book", required = listOf(specialty),
        goal = { "Book a clinic visit with a ${it.doctor ?: it.specialty}${it.area?.let { a -> " in $a" } ?: ""} on Practo${it.time?.let { t -> " around $t" } ?: ""}." },
        addendum = "Search the specialty, read the top 3 doctors (name, fee, distance, next slot) as ONE ask_user, " +
            "open the chosen doctor, pick the slot, wrap the final booking button in confirm.",
    )

    fun forIntent(intent: String): TaskSpec? = when (intent) {
        "book_cab" -> UBER
        "book_house_help" -> HOUSE_HELP
        "shop_online" -> SHOP
        "book_doctor" -> PRACTO
        else -> null
    }
}
