package com.saathi.app.features.care

/**
 * One spoken answer to "What is the patient's name and age?": "Kamla Devi, 72", "mera naam Ramesh hai aur umar
 * 68 saal", "सुनीता, 70 साल". Pure, so it is JVM-tested. Either part may be missing; the caller asks only for that.
 */
object PatientReply {
    data class Parsed(val name: String?, val age: Int?)

    private val AGE = Regex("\\b(\\d{1,3})\\b")
    private val FILLER = setOf(
        "my", "name", "is", "the", "patient", "patients", "patient's", "age", "aged", "and", "he", "she", "his", "her",
        "years", "year", "old", "yrs", "yr", "saal", "sal", "baras", "ka", "ki", "ke", "hai", "hain", "umar", "umr",
        "umra", "naam", "mera", "meri", "unka", "unki", "uska", "uski", "aur", "inka", "inki", "it's", "its", "i", "am",
        "मेरा", "मेरी", "नाम", "है", "हैं", "उम्र", "उमर", "साल", "वर्ष", "और", "की", "का", "के", "उनका", "उनकी",
    )

    fun parse(text: String?): Parsed {
        if (text.isNullOrBlank()) return Parsed(null, null)
        val age = AGE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..120 }
        val words = AGE.replace(text, " ")
            .split(Regex("[\\s,.!?।]+"))
            .filter { it.isNotBlank() && it.lowercase() !in FILLER }
        val name = words.take(4).joinToString(" ").takeIf { it.isNotBlank() }
        return Parsed(name, age)
    }
}
