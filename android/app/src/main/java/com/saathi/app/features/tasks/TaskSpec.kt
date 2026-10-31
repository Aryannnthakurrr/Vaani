package com.saathi.app.features.tasks

import android.net.Uri
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
    val deeplink: ((Slots) -> String?)? = null,
)

/** Deep links built by Saathi's own code. These are the only URIs the agent may open. */
object DeepLinks {
    /**
     * Uber universal deep link. Uber needs coordinates for the drop-off; without them we open the app and let
     * the agent type the destination instead.
     */
    fun uber(destination: String, lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        return Uri.Builder().scheme("uber").authority("")
            .appendQueryParameter("action", "setPickup")
            .appendQueryParameter("pickup", "my_location")
            .appendQueryParameter("dropoff[latitude]", lat.toString())
            .appendQueryParameter("dropoff[longitude]", lng.toString())
            .appendQueryParameter("dropoff[nickname]", destination)
            .build().toString()
    }

    /** Pure variant (no android.net.Uri) used by JVM tests and anywhere Uri is unavailable. */
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
        goal = { "Book the cheapest non-shared Uber ride to ${it.destination}." },
        addendum = "Landmarks: 'Where to?' search box, ride list with fares, a 'Confirm …' button. " +
            "Stop after the ride is requested and a driver/vehicle/'arriving' line is visible; read driver, car and ETA.",
        deeplink = { DeepLinks.uber(it.destination.orEmpty(), null, null) },
    )
    val HOUSE_HELP = TaskSpec(
        id = "book_house_help", pkg = Packages.HOUSE_HELP, scenario = "house_help", required = emptyList(),
        goal = { "Book ${it.service ?: "home cleaning"} on Urban Company${it.date?.let { d -> " for $d" } ?: ""}${it.time?.let { t -> " at $t" } ?: ""}." },
        addendum = "Pick the service, then the earliest matching slot. The final 'Confirm booking' must be wrapped in confirm. Never pay.",
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
