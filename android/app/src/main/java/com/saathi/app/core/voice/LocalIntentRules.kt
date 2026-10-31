package com.saathi.app.core.voice

import com.saathi.app.core.net.IntentResult
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.net.Slots

/**
 * Keyword intent routing in English, Hindi and Hinglish (SPEC §6.6). Used when the backend is down and by the
 * fake backend; mirrors the backend mock rules.
 */
object LocalIntentRules {
    private val RULES: List<Pair<String, List<String>>> = listOf(
        // First: symptoms and emergencies go to triage (features/care); "doctor" alone books Practo.
        "care" to listOf("not feeling well", "unwell", "tabiyat", "tabiat", "bimar", "bimaar", "symptom", "ambulance", "emergency",
            "dard", "pain", "bukhar", "fever", "chakkar", "dizzy", "vomit", "ulti", "saans", "बीमार", "तबीयत", "दर्द", "बुखार", "एम्बुलेंस"),
        "camera" to listOf("camera", "scan", "expiry", "mrp", "packet", "label", "कैमरा", "एक्सपायरी", "padh ke", "dekh ke batao"),
        // Sending a photo/message is taught step by step, never done for the user.
        "guide" to listOf("show me", "how do i", "how to", "teach", "kaise", "sikhao", "dikhao", "bhejni", "bhejna", "bhejun",
            "कैसे", "सिखाओ", "दिखाओ", "भेजनी", "भेजना"),
        "book_cab" to listOf("cab", "taxi", "uber", "ola", "ride", "auto", "bike", "moto", "कैब", "टैक्सी", "गाड़ी", "gaadi", "gadi", "ऑटो", "बाइक"),
        "book_house_help" to listOf("house help", "maid", "cleaning", "cleaner", "bai", "kaamwali", "safai", "सफाई", "बाई", "pronto",
            "bartan", "jhadu", "pocha", "बर्तन", "झाड़ू", "पोछा"),
        "book_doctor" to listOf("doctor", "practo", "appointment", "cardiolog", "dentist", "physician", "डॉक्टर", "daktar", "dr."),
        "shop_online" to listOf("amazon", "flipkart", "order", "buy", "cart", "kharid", "mangwa", "manga", "खरीद", "मंगा", "ऑर्डर"),
        "digest" to listOf("update", "digest", "bill", "messages", "what's new", "kya aaya", "khabar", "संदेश", "अपडेट", "बिल"),
        "settings" to listOf("setting", "language", "bhasha", "सेटिंग", "भाषा"),
        "chitchat" to listOf("hello", "hi saathi", "namaste", "kaise ho", "how are you", "thank", "dhanyavad", "shukriya", "नमस्ते", "धन्यवाद"),
    )

    /** Keywords must start a word, so "ola" doesn't fire on "chocolate" or "ride" on "pride". */
    private val MATCHERS = RULES.map { (intent, words) ->
        intent to words.map { Regex("(^|[^\\p{L}\\p{M}])" + Regex.escape(it)) }
    }

    private val DESTINATION = listOf(
        Regex("(?i)\\bto\\s+(?:the\\s+)?([\\p{L} .'-]{2,60}?)(?:\\s+(?:please|now|by|at|for)\\b|[.,?!]|$)"),
        Regex("(?i)^(?:mujhe\\s+)?([\\p{L} .'-]{2,60}?)\\s+(?:jaana|jana|jaane|jane)\\b"),
        Regex("(?i)([\\p{L} .'-]{2,60}?)\\s+(?:ke liye|tak)\\b"),
        Regex("([\\p{L}\\p{M} .'-]{2,60}?)\\s+(?:जाना|जाने|के लिए|तक)"),
    )
    private val PRODUCT = Regex("(?i)(?:order|buy|mangwa(?:o|na|do)?|manga(?:o|do)?|kharid(?:o|na)?)\\s+(?:me\\s+|some\\s+|a\\s+)?([\\p{L}0-9 .'-]{2,50})")
    /** Hinglish puts the product before the verb: "Flipkart se Parle-G biscuit mangao". */
    private val PRODUCT_BEFORE_VERB = Regex("(?i)^(.{2,50}?)\\s+(?:order\\s+kar|mangwa|manga|kharid)")
    /** The app name is a preference, not part of the product. */
    private val APP_WORDS = Regex("(?i)^(?:flipkart|amazon)\\s+(?:se|par|pe|on|from)\\s+|\\s+(?:from|on|se|par|pe)\\s+(?:flipkart|amazon)$")

    private fun product(u: String): String? =
        (PRODUCT.find(u) ?: PRODUCT_BEFORE_VERB.find(u))?.groupValues?.get(1)?.trim()?.trimEnd('.')
            ?.replace(APP_WORDS, "")?.trim()?.ifBlank { null }
    private val SPECIALTY = Regex("(?i)(cardiologist|heart|dentist|dermatologist|skin|eye|ent|orthopedic|bone|general physician|physician|diabetes|child)")

    fun route(utterance: String): IntentResult {
        val style = LanguageStyle.detect(utterance)
        val t = utterance.lowercase()
        val intent = MATCHERS.firstOrNull { (_, res) -> res.any { it.containsMatchIn(t) } }?.first ?: "unknown"
        val slots = when (intent) {
            "book_cab" -> Slots(destination = destination(utterance), vehicle = when {
                Regex("(^|[^\\p{L}])(auto|rickshaw)|ऑटो").containsMatchIn(t) -> "auto"
                Regex("(^|[^\\p{L}])(bike|moto)|बाइक").containsMatchIn(t) -> "bike"
                else -> null
            })
            "shop_online" -> Slots(product = product(utterance))
            "book_doctor" -> Slots(specialty = SPECIALTY.find(utterance)?.value?.lowercase()?.let(::specialty))
            // Pronto books help by time, so the day and time matter more than the chore (none unless one is named).
            "book_house_help" -> Slots(service = when {
                t.contains("kitchen") || t.contains("rasoi") || t.contains("रसोई") -> "kitchen cleaning"
                t.contains("bartan") || t.contains("dish") || t.contains("बर्तन") -> "dishwashing"
                t.contains("kapde") || t.contains("laundry") || t.contains("कपड़े") -> "laundry"
                Regex("safai|safaai|clean|jhadu|jhaadu|pocha|सफाई|सफ़ाई|झाड़ू|पोछा").containsMatchIn(t) -> "home cleaning"
                else -> null
            }, date = day(t), time = clock(t))
            else -> Slots()
        }
        return IntentResult(intent, slots, style.wire, reply(intent, slots, style))
    }

    /** "kal" in a booking means tomorrow. */
    private fun day(t: String): String? = when {
        Regex("(^|[^\\p{L}])(tomorrow|kal)([^\\p{L}]|$)|कल").containsMatchIn(t) -> "tomorrow"
        Regex("(^|[^\\p{L}])(today|aaj)([^\\p{L}]|$)|आज").containsMatchIn(t) -> "today"
        else -> null
    }

    /** "10 baje", "10:30 am", "4 बजे", or a part of the day. */
    private fun clock(t: String): String? {
        Regex("(\\d{1,2}(?::\\d{2})?)\\s*(am|pm|baje|बजे)").find(t)?.let { m ->
            return m.groupValues[1] + " " + m.groupValues[2].let { if (it == "बजे") "baje" else it }
        }
        return when {
            Regex("subah|morning|सुबह").containsMatchIn(t) -> "morning"
            Regex("dopahar|afternoon|दोपहर").containsMatchIn(t) -> "afternoon"
            Regex("shaam|sham |evening|शाम").containsMatchIn(t) -> "evening"
            else -> null
        }
    }

    private fun destination(u: String): String? {
        // "go", "to", "reach": "I want to go to Connaught Place" is a trip to Connaught Place, not to "Go to …".
        val stop = setOf("cab", "taxi", "uber", "ride", "auto", "bike", "book", "mujhe", "a", "an", "the", "kar", "do", "go", "to", "reach", "get")
        for (re in DESTINATION) {
            val m = re.find(u) ?: continue
            val words = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.lowercase() !in stop && it != "कैब" }
            val dest = words.joinToString(" ").trim()
            if (dest.length >= 2) return dest.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    private fun specialty(word: String) = when (word) {
        "heart" -> "cardiologist"
        "skin" -> "dermatologist"
        "bone" -> "orthopedic"
        "eye" -> "ophthalmologist"
        else -> word
    }

    fun reply(intent: String, slots: Slots, style: ReplyStyle): String = when (intent) {
        "book_cab" -> {
            val (en, hi) = when (slots.vehicle) { "auto" -> "an auto" to "ऑटो"; "bike" -> "a bike" to "बाइक"; else -> "a cab" to "कैब" }
            val hl = when (slots.vehicle) { "auto" -> "auto"; "bike" -> "bike"; else -> "cab" }
            slots.destination?.let {
                tr(style, "Okay, I'll book $en to $it.", "ठीक है, $it के लिए $hi बुक करता हूँ।", "Theek hai, $it ke liye $hl book karta hoon.")
            } ?: tr(style, "Okay, let's book $en. Where do you want to go?", "ठीक है, $hi बुक करते हैं। कहाँ जाना है?", "Theek hai, $hl book karte hain. Kahan jaana hai?")
        }
        "book_house_help" -> tr(style, "Okay, let's book house help.", "ठीक है, घर की सफाई बुक करते हैं।", "Theek hai, house help book karte hain.")
        "care" -> tr(style, "I'm sorry you're not well. Let me ask a few questions.", "आपकी तबीयत ठीक नहीं, मुझे दुख है। मैं कुछ सवाल पूछूँगा।",
            "Aapki tabiyat theek nahi, mujhe afsos hai. Main kuch sawaal poochunga.")
        "book_doctor" -> tr(style, "Okay, let's find a doctor.", "ठीक है, डॉक्टर ढूँढते हैं।", "Theek hai, doctor dhoondhte hain.")
        "shop_online" -> tr(style, "Okay, let's order ${slots.product ?: "it"}.", "ठीक है, ${slots.product ?: "सामान"} मंगाते हैं।", "Theek hai, ${slots.product ?: "saamaan"} mangwate hain.")
        "guide" -> tr(style, "Sure, I'll show you step by step.", "ज़रूर, मैं आपको एक-एक कदम दिखाता हूँ।", "Zaroor, main aapko step by step dikhata hoon.")
        "camera" -> tr(style, "Opening the camera. Hold the pack in front of it.", "कैमरा खोल रहा हूँ। पैकेट कैमरे के सामने रखिए।", "Camera khol raha hoon. Packet camera ke saamne rakhiye.")
        "digest" -> tr(style, "Here are your updates.", "ये रहे आपके अपडेट।", "Ye rahe aapke updates.")
        "settings" -> tr(style, "Opening settings.", "सेटिंग्स खोल रहा हूँ।", "Settings khol raha hoon.")
        "chitchat" -> tr(style, "Namaste! I'm here to help.", "नमस्ते! मैं आपकी मदद के लिए हूँ।", "Namaste! Main aapki madad ke liye hoon.")
        else -> tr(style, "Sorry, I didn't understand. You can say: book a cab, or show me how.",
            "माफ़ कीजिए, मैं समझ नहीं पाया। आप कह सकते हैं: कैब बुक करो, या मुझे दिखाओ।",
            "Maaf kijiye, main samjha nahi. Aap keh sakte hain: cab book karo, ya mujhe dikhao.")
    }
}
