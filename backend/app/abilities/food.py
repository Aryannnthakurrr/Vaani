"""Sugar and salt warnings for the shopping camera, decided by fixed code instead of the camera model's judgement.

The camera model reads the pack (name, quantity and, when the panel is visible, sugar and sodium per 100 g or ml).
This module decides whether that matters for this user: diabetes → sugar, high blood pressure → sodium. Thresholds
are the UK FSA front-of-pack "high" values, the usual reference for a traffic-light label:
- sugar: high above 22.5 g/100 g in food, above 11.25 g/100 ml in drinks (a drink above 5 g/100 ml is "medium");
- sodium: high above 600 mg/100 g (1.5 g salt), medium above 120 mg/100 g (0.3 g salt).
When the label isn't readable, typical label values for common Indian packs fill in (`KNOWN`, marked "about").
"""

from __future__ import annotations

import re
from typing import Any

# Typical printed values per 100 g or 100 ml (from the packs' nutrition panels; brands vary by variant).
KNOWN: list[tuple[str, dict[str, Any]]] = [
    (r"frooti", {"per": "ml", "sugar_g": 14.6, "sodium_mg": 10}),
    (r"maaza|slice", {"per": "ml", "sugar_g": 14.0, "sodium_mg": 10}),
    (
        r"coca.?cola|coke|thums.?up|pepsi|sprite|fanta|limca|mountain dew",
        {"per": "ml", "sugar_g": 10.6, "sodium_mg": 10},
    ),
    (r"real (fruit )?juice|tropicana", {"per": "ml", "sugar_g": 12.0, "sodium_mg": 10}),
    (r"lay'?s|lays", {"per": "g", "sugar_g": 2.0, "sodium_mg": 690}),
    (r"kurkure", {"per": "g", "sugar_g": 3.0, "sodium_mg": 730}),
    (r"bingo|uncle chipps|pringles|chips", {"per": "g", "sugar_g": 2.5, "sodium_mg": 650}),
    (r"bhujia|namkeen|haldiram|mixture", {"per": "g", "sugar_g": 3.0, "sodium_mg": 900}),
    (r"maggi|yippee|noodles", {"per": "g", "sugar_g": 2.0, "sodium_mg": 1250}),
    (r"parle.?g|marie|good ?day|bourbon|oreo|biscuit", {"per": "g", "sugar_g": 25.0, "sodium_mg": 300}),
    (
        r"gulab jamun|rasgulla|soan papdi|chocolate|dairy milk|kitkat|5 ?star",
        {"per": "g", "sugar_g": 45.0, "sodium_mg": 80},
    ),
    (r"pickle|achaar|achar", {"per": "g", "sugar_g": 2.0, "sodium_mg": 2400}),
]


def conditions_from(*texts: str) -> set[str]:
    """`diabetes` and `bp` from whatever the app or memory says about the user's health."""
    said = " ".join(texts).lower()
    found = set()
    if re.search(r"diabet|madhumeh|मधुमेह|डायबिटीज|sugar (patient|ki bimari)|blood sugar", said):
        found.add("diabetes")
    if re.search(r"blood pressure|hypertension|\bbp\b|high bp|उच्च रक्तचाप|ब्लड प्रेशर", said):
        found.add("bp")
    return found


def _ml_or_g(quantity: str | None) -> tuple[float | None, str | None]:
    m = re.search(r"(\d+(?:\.\d+)?)\s*(ml|l|g|kg|gm|gram)", (quantity or "").lower())
    if not m:
        return None, None
    n, unit = float(m.group(1)), m.group(2)
    if unit == "l":
        return n * 1000, "ml"
    if unit == "kg":
        return n * 1000, "g"
    return n, "ml" if unit == "ml" else "g"


def nutrition(card: dict[str, Any]) -> dict[str, Any] | None:
    """Per-100 values from the label, else the typical values for a known pack, else None."""
    per = card.get("per") or (
        "ml" if re.search(r"\bml\b|litre|\bl\b", str(card.get("quantity") or "")) else "g"
    )
    if card.get("sugar_g_per_100") is not None or card.get("sodium_mg_per_100") is not None:
        return {"per": per, "sugar_g": card.get("sugar_g_per_100"), "sodium_mg": card.get("sodium_mg_per_100"),
                "typical": False}  # fmt: skip
    name = str(card.get("name") or "").lower()
    for pattern, values in KNOWN:
        if re.search(pattern, name):
            return {**values, "typical": True}
    return None


TEXT = {
    "sugar_high": {
        "en": "Careful, this is very sugary: {amount}. With your diabetes, maybe pick a sugar-free option or ask your doctor.",
        "hi": "ध्यान दीजिए, इसमें बहुत चीनी है: {amount}। आपकी शुगर के लिए बिना चीनी वाला कुछ चुनिए, या डॉक्टर से पूछिए।",
        "hinglish": "Dhyaan dijiye, ismein bahut sugar hai: {amount}. Aapki diabetes ke liye sugar-free option lijiye ya doctor se poochiye.",
    },
    "sugar_medium": {
        "en": "This has quite a lot of sugar for a drink: {amount}. Plain water or chaach is kinder to your sugar.",
        "hi": "इस पेय में काफ़ी चीनी है: {amount}। सादा पानी या छाछ आपकी शुगर के लिए बेहतर है।",
        "hinglish": "Is drink mein kaafi sugar hai: {amount}. Saada paani ya chaach aapki sugar ke liye behtar hai.",
    },
    "salt_high": {
        "en": "Careful, this is very salty: {amount}. With your blood pressure, maybe roasted makhana or chana instead.",
        "hi": "ध्यान दीजिए, इसमें बहुत नमक है: {amount}। आपके बीपी के लिए भुना मखाना या चना बेहतर रहेगा।",
        "hinglish": "Dhyaan dijiye, ismein bahut namak hai: {amount}. Aapke BP ke liye bhuna makhana ya chana behtar rahega.",
    },
    "salt_medium": {
        "en": "This is fairly salty: {amount}. Fine now and then, with your blood pressure.",
        "hi": "इसमें ठीक-ठाक नमक है: {amount}। बीपी के साथ कभी-कभार ठीक है।",
        "hinglish": "Ismein theek-thaak namak hai: {amount}. BP ke saath kabhi-kabhaar theek hai.",
    },
}


def _amount_sugar(sugar: float, pack: float | None, style: str) -> str:
    if pack:
        grams = sugar * pack / 100
        spoons = max(1, round(grams / 4))  # a teaspoon of sugar is about 4 g
        return {"en": f"about {spoons} teaspoons in this pack", "hi": f"इस पैक में लगभग {spoons} चम्मच",
                "hinglish": f"is pack mein lagbhag {spoons} chammach"}.get(style, f"about {spoons} teaspoons")  # fmt: skip
    return {
        "en": f"{sugar:g} g per 100",
        "hi": f"हर 100 में {sugar:g} ग्राम",
        "hinglish": f"har 100 mein {sugar:g} gram",
    }.get(style, f"{sugar:g} g per 100")


def _amount_salt(sodium: float, pack: float | None, style: str) -> str:
    salt = sodium * 2.5 / 1000  # grams of salt per 100
    if pack:
        grams = round(salt * pack / 100, 1)
        share = round(grams / 5 * 100)
        return {"en": f"{grams:g} g of salt in this pack, {share}% of a day's limit",
                "hi": f"इस पैक में {grams:g} ग्राम नमक, दिन की सीमा का {share}%",
                "hinglish": f"is pack mein {grams:g} gram namak, din ki limit ka {share}%"}.get(style, f"{grams:g} g salt")  # fmt: skip
    return {"en": f"{salt:.1f} g salt per 100 g", "hi": f"हर 100 ग्राम में {salt:.1f} ग्राम नमक",
            "hinglish": f"har 100 gram mein {salt:.1f} gram namak"}.get(style, f"{salt:.1f} g salt per 100 g")  # fmt: skip


def check(card: dict[str, Any], conditions: set[str], style: str = "en") -> tuple[str, str] | None:
    """(level, text) of the one warning that matters for this user and this pack, or None."""
    if not conditions:
        return None
    values = nutrition(card)
    if values is None:
        return None
    pack, unit = _ml_or_g(card.get("quantity"))
    pack = pack if unit == values["per"] else None
    about = values.get("typical")
    drink = values["per"] == "ml"
    sugar, sodium = values.get("sugar_g"), values.get("sodium_mg")
    found: tuple[str, str, str] | None = None
    if "diabetes" in conditions and sugar is not None:
        if sugar > (11.25 if drink else 22.5):
            found = ("high", "sugar_high", _amount_sugar(sugar, pack, style))
        elif drink and sugar > 5:
            found = ("medium", "sugar_medium", _amount_sugar(sugar, pack, style))
    if found is None and "bp" in conditions and sodium is not None:
        if sodium > 600:
            found = ("high", "salt_high", _amount_salt(sodium, pack, style))
        elif sodium > 120 and not drink:
            found = ("medium", "salt_medium", _amount_salt(sodium, pack, style))
    if found is None:
        return None
    level, key, amount = found
    if about:
        amount = {
            "en": "typically " + amount,
            "hi": "आम तौर पर " + amount,
            "hinglish": "aam taur par " + amount,
        }.get(style, amount)
    return level, TEXT[key].get(style, TEXT[key]["en"]).format(amount=amount)
