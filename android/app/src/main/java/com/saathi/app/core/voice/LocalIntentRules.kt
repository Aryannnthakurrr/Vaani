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
        "camera" to listOf("camera", "scan", "expiry", "mrp", "packet", "label", "कैमरा", "एक्सपायरी", "padh ke", "dekh ke batao"),
        "guide" to listOf("show me", "how do i", "how to", "teach", "kaise", "sikhao", "dikhao", "कैसे", "सिखाओ", "दिखाओ"),
        "book_cab" to listOf("cab", "taxi", "uber", "ola", "ride", "auto", "कैब", "टैक्सी", "गाड़ी", "gaadi", "gadi"),
        "book_house_help" to listOf("house help", "maid", "cleaning", "cleaner", "bai", "kaamwali", "safai", "सफाई", "बाई", "urban company"),
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
    private val SPECIALTY = Regex("(?i)(cardiologist|heart|dentist|dermatologist|skin|eye|ent|orthopedic|bone|general physician|physician|diabetes|child)")

    fun route(utterance: String): IntentResult {
        val style = LanguageStyle.detect(utterance)
        val t = utterance.lowercase()
        val intent = MATCHERS.firstOrNull { (_, res) -> res.any { it.containsMatchIn(t) } }?.first ?: "unknown"
        val slots = when (intent) {
            "book_cab" -> Slots(destination = destination(utterance))
            "shop_online" -> Slots(product = PRODUCT.find(utterance)?.groupValues?.get(1)?.trim()?.trimEnd('.'))
            "book_doctor" -> Slots(specialty = SPECIALTY.find(utterance)?.value?.lowercase()?.let(::specialty))
            "book_house_help" -> Slots(service = if (t.contains("kitchen")) "kitchen cleaning" else "home cleaning")
            else -> Slots()
        }
        return IntentResult(intent, slots, style.wire, reply(intent, slots, style))
    }

    private fun destination(u: String): String? {
        val stop = setOf("cab", "taxi", "uber", "ride", "book", "mujhe", "a", "the", "kar", "do")
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
        "book_cab" -> slots.destination?.let {
            tr(style, "Okay, I'll book a cab to $it.", "ठीक है, $it के लिए कैब बुक करता हूँ।", "Theek hai, $it ke liye cab book karta hoon.")
        } ?: tr(style, "Okay, let's book a cab. Where do you want to go?", "ठीक है, कैब बुक करते हैं। कहाँ जाना है?", "Theek hai, cab book karte hain. Kahan jaana hai?")
        "book_house_help" -> tr(style, "Okay, let's book house help.", "ठीक है, घर की सफाई बुक करते हैं।", "Theek hai, house help book karte hain.")
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
