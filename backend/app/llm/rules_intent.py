"""Keyword intent router for EN / HI (Devanagari) / Hinglish. Used in mock mode and as the Gemini fallback."""

from __future__ import annotations

import re

from app.schemas import IntentResponse, Slots

# Order matters: the first intent whose pattern matches wins.
_INTENTS: list[tuple[str, str]] = [
    (
        # First: symptoms and emergencies go to triage (docs/CARE.md), even "call an ambulance".
        "care",
        r"not feeling well|feeling (?:unwell|sick)|\bunwell\b|tabiy?at|tabeeyat|bimaa?r|\bsymptom|\bambulance\b|"
        r"emergency|\bdard\b|\bpain\b|bukhaa?r|\bfever\b|chakkar|dizzy|vomit|\bulti\b|breathless|saa?ns|"
        r"बीमार|तबीयत|दर्द|बुखार|एम्बुलेंस|चक्कर",
    ),
    (
        "chitchat",
        r"^(hi|hello|hey|namaste|namaskar|नमस्ते|नमस्कार)[\s!.,]*(vaani|vani|saathi|वाणी|साथी)?[\s!.,]*$|"
        r"\bhow are you\b|\bkaise ho\b|\bkaisi ho\b|कैसे हो|कैसी हो",
    ),
    (
        "guide",
        r"\bhow (do|can|to) (i|we)?\b|\bhow to\b|\bshow me how\b|\bteach me\b|\bkaise\b|\bsikha|कैसे|सिखा|"
        # "mujhe ye photo Aarav ko bhejni hai": sending something is taught, never done for the user.
        r"\b(photo|pic|picture|video|message)\b.*\b(bhej|send|share)|\b(bhejni|bhejna|bhejun|bhejo)\b|"
        r"\b(send|share) (this|it|ye|yeh)\b|भेजनी|भेजना|भेजो|भेज दो",
    ),
    ("camera", r"\bcamera\b|\bscan\b|\bexpiry\b|\bmrp\b|\blabel\b|\bpacket\b|कैमरा|एक्सपायरी|\bpadh(o|kar)\b"),
    (
        "book_cab",
        r"\bcab\b|\btaxi\b|\buber\b|\bola\b|\bride\b|\bauto\b|\bbike\b|\bmoto\b|\bgaadi\b|\bgadi\b|कैब|टैक्सी|गाड़ी|गाडी|ऑटो|बाइक",
    ),
    (
        "book_doctor",
        r"\bdoctor\b|\bdr\.?\s|\bpracto\b|\bappointment\b|\bdaktar\b|\bdentist\b|\bcardiolog|"
        r"\bspecialist\b|डॉक्टर|डाक्टर|अपॉइंटमेंट",
    ),
    (
        "book_house_help",
        r"\bmaid\b|\bcleaning\b|\bhouse ?help\b|\bsafai\b|\bkaamwali\b|\bbai\b|\bcook\b|"
        r"\bpronto\b|\bbartan\b|\bjhadu\b|\bpocha\b|\bdishes\b|\blaundry\b|"
        # "mujhe help chahiye": in Hindi "help" on its own usually means a domestic helper.
        r"\bhelp chahiye\b|\bhelper\b|\bkaam ?wa+li\b|\bghar (ka|ke) kaam\b|"
        r"सफाई|सफ़ाई|कामवाली|बर्तन|झाड़ू|पोछा|हेल्प|घर का काम|घर के काम",
    ),
    (
        "shop_online",
        r"\border\b|\bamazon\b|\bflipkart\b|\bbuy\b|\bcart\b|\bkharid|\bmangwa|\bmanga|खरीद|मंगवा|मंगा|ऑर्डर",
    ),
    (
        "digest",
        r"\bupdates?\b|\bdigest\b|\bmessages?\b|\bbills?\b|\bkya aaya\b|\bsummary\b|\bnotifications?\b|"
        r"संदेश|मैसेज|बिल",
    ),
    ("settings", r"\bsettings?\b|सेटिंग"),
]

_SPECIALTIES = {
    "cardiologist": r"cardiolog|heart|dil\b|दिल|हृदय",
    "dentist": r"dentist|teeth|tooth|daant|दांत|दाँत",
    "ophthalmologist": r"eye|aankh|आँख|आंख",
    "dermatologist": r"skin|derma|chamdi|त्वचा",
    "orthopedist": r"bone|knee|joint|ortho|haddi|घुटन|हड्डी",
    "general physician": r"fever|bukhar|बुखार|general|physician",
}
# Pronto's chores, most specific first; anything else is plain home cleaning.
_HOUSE_SERVICES = {
    "kitchen cleaning": r"kitchen|rasoi|रसोई",
    "bathroom cleaning": r"bathroom|toilet|बाथरूम",
    "dishwashing": r"dish|bartan|बर्तन",
    "laundry": r"laundry|kapde|कपड़े",
    "cooking": r"\bcook|khana|खाना",
    "home cleaning": r"safai|safaai|clean|jhadu|jhaadu|pocha|सफाई|सफ़ाई|झाड़ू|पोछा",
}
_FILLER = r"^(please |mujhe |muje |main |mai |i want to go to |i need to go to |मुझे |मैं )+"
# "Flipkart se biscuit" / "biscuit from Amazon" -> "biscuit": the app is a preference, not the product.
_APP_WORDS = (
    r"^(?:flipkart|amazon)\s+(?:se|par|pe|on|from)\s+|\s+(?:from|on|se|par|pe)\s+(?:flipkart|amazon)$"
)

_REPLIES: dict[str, dict[str, str]] = {
    "book_cab": {
        "en": "Okay, I will book a cab{to_en}.",
        "hi": "ठीक है, मैं{to_hi} कैब बुक करती हूँ।",
        "hinglish": "Theek hai,{to_hl} cab book karti hoon.",
    },
    "book_house_help": {
        "en": "Okay, let me book house help for you.",
        "hi": "ठीक है, मैं घर की मदद बुक करती हूँ।",
        "hinglish": "Theek hai, main ghar ki help book karti hoon.",
    },
    "shop_online": {
        "en": "Okay, I will find {product_en} online.",
        "hi": "ठीक है, मैं {product_hi} ऑनलाइन ढूँढती हूँ।",
        "hinglish": "Theek hai, main {product_hl} online dhoondhti hoon.",
    },
    "care": {
        "en": "I'm sorry you're not well. Let me ask a few questions.",
        "hi": "आपकी तबीयत ठीक नहीं, मुझे दुख है। मैं कुछ सवाल पूछूँगी।",
        "hinglish": "Aapki tabiyat theek nahi, mujhe afsos hai. Main kuch sawaal poochungi.",
    },
    "book_doctor": {
        "en": "Okay, I will look for a {spec} for you.",
        "hi": "ठीक है, मैं आपके लिए डॉक्टर ढूँढती हूँ।",
        "hinglish": "Theek hai, main aapke liye {spec} dhoondhti hoon.",
    },
    "guide": {
        "en": "Sure, I will show you step by step.",
        "hi": "ज़रूर, मैं आपको एक-एक कदम दिखाती हूँ।",
        "hinglish": "Zaroor, main aapko step by step dikhati hoon.",
    },
    "camera": {
        "en": "Opening the camera. Hold the packet in front of it.",
        "hi": "कैमरा खोल रही हूँ। पैकेट सामने रखिए।",
        "hinglish": "Camera khol rahi hoon. Packet saamne rakhiye.",
    },
    "digest": {
        "en": "Here are your updates.",
        "hi": "यह रहे आपके आज के संदेश।",
        "hinglish": "Yeh rahe aapke updates.",
    },
    "settings": {"en": "Opening settings.", "hi": "सेटिंग्स खोल रही हूँ।", "hinglish": "Settings khol rahi hoon."},
    "chitchat": {
        "en": "Namaste! I am Vaani. How can I help you?",
        "hi": "नमस्ते! मैं वाणी हूँ। मैं आपकी क्या मदद करूँ?",
        "hinglish": "Namaste! Main Vaani hoon. Aapki kya madad karoon?",
    },
    "unknown": {
        "en": "Sorry, I did not understand. You can say: book a cab, or show me how.",
        "hi": "माफ़ कीजिए, मैं समझ नहीं पाई। आप बोल सकते हैं: कैब बुक करो।",
        "hinglish": "Maaf kijiye, main samjhi nahi. Aap bol sakte hain: cab book karo.",
    },
}


def detect_intent(text: str) -> str:
    low = text.lower().strip()
    for intent, pattern in _INTENTS:
        if re.search(pattern, low):
            return intent
    return "unknown"


def _clean(value: str | None) -> str | None:
    if not value:
        return None
    value = re.sub(_FILLER, "", value.strip(" .,!?।"), flags=re.IGNORECASE).strip()
    value = re.sub(_APP_WORDS, "", value, flags=re.IGNORECASE).strip()
    return value or None


def extract_slots(text: str, intent: str) -> Slots:
    low = text.strip()
    slots = Slots()
    if intent == "book_cab":
        m = (
            # "want to go to X": skip "go to" / "reach" so the destination is X, not "go to X".
            re.search(
                r"\bto (?:the )?(?:go (?:to )?(?:the )?|reach (?:the )?|get to (?:the )?)?(.+?)(?:\s+(?:please|now|by .+))?$",
                low,
                re.IGNORECASE,
            )
            or re.search(r"^(.+?)\s+(?:jaana|jana|jaane|jaani|jane)\b", low, re.IGNORECASE)
            or re.search(r"^(.+?)\s+ke\s+liye\b", low, re.IGNORECASE)
            or re.search(r"^(.+?)\s+(?:जाना|जाने|के लिए|तक)", low)
        )
        dest = _clean(m.group(1)) if m else None
        if dest and not re.search(r"\b(cab|taxi|uber|book)\b|कैब", dest, re.IGNORECASE):
            slots.destination = dest
        if re.search(r"\bauto\b|ऑटो|\brickshaw\b", low, re.IGNORECASE):
            slots.vehicle = "auto"
        elif re.search(r"\bbike\b|\bmoto\b|बाइक", low, re.IGNORECASE):
            slots.vehicle = "bike"
    elif intent == "shop_online":
        m = (
            re.search(
                r"\b(?:order|buy)\s+(?:me\s+)?(?:some\s+|a\s+|an\s+)?(.+?)(?:\s+(?:on|from)\s+\w+)?$",
                low,
                re.IGNORECASE,
            )
            or re.search(r"^(.+?)\s+(?:order|mangwa|manga|kharid)", low, re.IGNORECASE)
            or re.search(r"^(.+?)\s+(?:ऑर्डर|मंगवा|मंगा|खरीद)", low)
        )
        slots.product = _clean(m.group(1)) if m else None
        q = re.search(r"\b(\d{1,2})\s*(?:packets?|pcs|pieces|kg)\b", low, re.IGNORECASE)
        slots.quantity = int(q.group(1)) if q else None
    elif intent == "book_doctor":
        for name, pattern in _SPECIALTIES.items():
            if re.search(pattern, low, re.IGNORECASE):
                slots.specialty = name
                break
        d = re.search(r"\b(?:dr\.?|doctor)\s+([A-Z][a-z]+)", text)
        slots.doctor = f"Dr. {d.group(1)}" if d else None
    elif intent == "book_house_help":
        for service, pattern in _HOUSE_SERVICES.items():
            if re.search(pattern, low, re.IGNORECASE):
                slots.service = service
                break  # nothing named: no chore (Pronto books help by time, not by chore)
    t = re.search(r"\b(\d{1,2}(?::\d{2})?\s*(?:am|pm|baje))\b|(\d{1,2})\s*बजे", low, re.IGNORECASE)
    if t:
        slots.time = (t.group(1) or f"{t.group(2)} baje").lower()
    if re.search(r"\btomorrow\b|\bkal\b|कल", low, re.IGNORECASE):
        slots.date = "tomorrow"
    elif re.search(r"\btoday\b|\baaj\b|आज", low, re.IGNORECASE):
        slots.date = "today"
    return slots


def reply_for(intent: str, slots: Slots, style: str) -> str:
    template = _REPLIES.get(intent, _REPLIES["unknown"])[style]
    dest = slots.destination
    product = slots.product or "that"
    return template.format(
        to_en=f" to {dest}" if dest else "",
        to_hi=f" {dest} के लिए" if dest else "",
        to_hl=f" {dest} ke liye" if dest else "",
        product_en=product,
        product_hi=slots.product or "वह सामान",
        product_hl=slots.product or "woh saamaan",
        spec=slots.specialty or "doctor",
    )


def route(text: str, style: str) -> IntentResponse:
    intent = detect_intent(text)
    slots = extract_slots(text, intent)
    return IntentResponse(
        intent=intent, slots=slots, reply_style=style, reply_text=reply_for(intent, slots, style)
    )
