package com.saathi.app.features.scam

import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr

/** Plain-language explanations for stored reason codes, for alerts and the Alerts history screen. */
object ReasonText {
    fun of(code: String, style: ReplyStyle): String {
        val key = code.substringBefore(':')
        return when (key) {
            "ussd_forwarding" -> tr(style, "Asks you to dial a code that sends your calls to a stranger.",
                "ऐसा कोड डायल करने को कहता है जिससे आपकी कॉल किसी अनजान के पास चली जाएँ।",
                "Aisa code dial karne ko kehta hai jisse aapki calls kisi anjaan ke paas chali jayein.")
            "ussd_upi" -> tr(style, "Asks you to dial the UPI payment code.", "UPI भुगतान वाला कोड डायल करने को कहता है।",
                "UPI payment wala code dial karne ko kehta hai.")
            "ussd_unknown" -> tr(style, "Asks you to dial an unusual phone code.", "अजीब फ़ोन कोड डायल करने को कहता है।",
                "Ajeeb phone code dial karne ko kehta hai.")
            "apk_link" -> tr(style, "Has a link that installs an app.", "इसमें ऐप डाउनलोड करने वाला लिंक है।",
                "Isme app download karne wala link hai.")
            "dangerous_link", "lookalike", "brand_unofficial" -> tr(style, "Has a fake website link.",
                "इसमें नकली वेबसाइट का लिंक है।", "Isme nakli website ka link hai.")
            "suspicious_link", "ip_host", "punycode", "shortener", "risky_tld" -> tr(style, "Has an unusual link.",
                "इसमें अजीब लिंक है।", "Isme ajeeb link hai.")
            "kyc" -> tr(style, "Says your KYC will expire. Banks do not do this by SMS.",
                "कहता है आपका KYC खत्म हो जाएगा। बैंक ऐसा SMS से नहीं करते।", "Kehta hai aapka KYC khatam ho jayega. Bank aisa SMS se nahi karte.")
            "account_blocked" -> tr(style, "Says your account will be blocked.", "कहता है आपका खाता बंद हो जाएगा।",
                "Kehta hai aapka account band ho jayega.")
            "electricity" -> tr(style, "Threatens to cut your electricity.", "बिजली काटने की धमकी देता है।",
                "Bijli kaatne ki dhamki deta hai.")
            "customs" -> tr(style, "Says a parcel is held and needs payment.", "कहता है आपका पार्सल रुका है और पैसे चाहिए।",
                "Kehta hai aapka parcel ruka hai aur paise chahiye.")
            "lottery" -> tr(style, "Says you won a prize.", "कहता है आपने इनाम जीता है।", "Kehta hai aapne inaam jeeta hai.")
            "police" -> tr(style, "Pretends to be police or a government officer.", "पुलिस या सरकारी अफ़सर होने का नाटक करता है।",
                "Police ya sarkari officer hone ka natak karta hai.")
            "refund_upi" -> tr(style, "Offers a refund but asks for your UPI PIN.", "रिफ़ंड का लालच देकर UPI पिन माँगता है।",
                "Refund ka laalach dekar UPI PIN maangta hai.")
            "qr_receive" -> tr(style, "Asks you to scan a QR code to receive money. Scanning only sends money.",
                "पैसे पाने के लिए QR स्कैन करने को कहता है। स्कैन करने से पैसे जाते हैं।",
                "Paise paane ke liye QR scan karne ko kehta hai. Scan karne se paise jaate hain.")
            "urgent_pay" -> tr(style, "Pushes you to pay urgently.", "जल्दी पैसे भेजने का दबाव डालता है।",
                "Jaldi paise bhejne ka dabaav daalta hai.")
            "unknown_caller" -> tr(style, "The caller is not in your contacts.", "कॉल करने वाला आपके कॉन्टैक्ट में नहीं है।",
                "Call karne wala aapke contacts mein nahi hai.")
            "otp_during_call" -> tr(style, "An OTP arrived during the call.", "कॉल के दौरान ओटीपी आया।", "Call ke dauraan OTP aaya.")
            "money_app_during_call" -> tr(style, "A payment app opened during the call.", "कॉल के दौरान पैसे वाला ऐप खुला।",
                "Call ke dauraan paise wala app khula.")
            "remote_app_during_call", "screen_share_during_call" -> tr(style, "Screen sharing started during the call.",
                "कॉल के दौरान स्क्रीन शेयर शुरू हुआ।", "Call ke dauraan screen share shuru hua.")
            "recent_scam_message" -> tr(style, "You got a scam-like message recently.", "हाल ही में धोखे वाला संदेश आया था।",
                "Haal hi mein dhokhe wala message aaya tha.")
            "long_call" -> tr(style, "The call has been long.", "कॉल काफ़ी लंबी चल रही है।", "Call kaafi lambi chal rahi hai.")
            else -> code
        }
    }

    fun join(codes: List<String>, style: ReplyStyle): String = codes.map { of(it, style) }.distinct().joinToString(" ")
}
