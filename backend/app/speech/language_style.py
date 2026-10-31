"""Classify a user turn as en | hi | hinglish.

Signals: share of Devanagari tokens, share of Latin tokens that are common Hindi words written
in Roman script, and the language code Sarvam detected. Sarvam STT runs in `codemix` mode, so
Hinglish speech arrives as a mix of Devanagari and Latin tokens.
"""

from __future__ import annotations

import re

_TOKEN = re.compile(r"[ऀ-ॿ]+|[A-Za-z']+")
_DEVANAGARI = re.compile(r"[ऀ-ॿ]")

# Common Hindi function words / verbs in Roman script. Kept to words that are rare in English.
HINGLISH_WORDS = frozenset(
    """
    hai hain tha thi the ho hoon hun hu kar karo karna karke kariye karo kijiye kijie dijiye dijie do
    de dena dedo kardo karwa karwana ke ki ka ko se mein me mai main mujhe mera meri mere hamara
    humko hum aap aapka aapki tum tumhara liye jaana jana jaane jaa ja chahiye chaiye kya kyun kaise
    kab kahan yahan wahan nahi nahin haan han ha ji theek thik accha acha abhi aaj kal raha rahi rahe
    gaya gayi gaye wala wali wale bhejo bhejna bhej batao bata bolo dikhao sikhao samjhao lagao
    lekin aur bhi sab kuch koi pe par tak beta bhai didi dada dadi nana nani mummy papa bahut zyada
    thoda paisa paise ghar dawai dawa daktar gaadi chalo ruko mat rehne
    """.split()
)
_WEAK = frozenset({"do", "me", "main", "ha", "the", "hum", "ja", "de", "pe", "par", "bata"})


def classify_style(text: str, language_code: str | None = None) -> str:
    tokens = _TOKEN.findall(text or "")
    if not tokens:
        return _from_code(language_code)
    dev = sum(1 for t in tokens if _DEVANAGARI.search(t))
    latin = [t.lower() for t in tokens if not _DEVANAGARI.search(t)]
    total = len(tokens)
    strong = sum(1 for t in latin if t in HINGLISH_WORDS and t not in _WEAK)
    weak = sum(1 for t in latin if t in _WEAK)
    # An English sentence stays English even when Sarvam's codemix writes a name in Devanagari ("send this photo
    # to केशव"): no Hindi words, at most two Devanagari words (a name), and at least three English words.
    if len(latin) >= 3 and strong == 0 and weak == 0 and dev <= 2:
        return "en"
    if dev and latin and len(latin) / total >= 0.2:
        return "hinglish"
    if dev / total >= 0.5:
        return "hi"
    hindiness = (strong + 0.5 * weak) / max(len(latin), 1)
    if strong >= 2 or (strong >= 1 and hindiness >= 0.3):
        return "hinglish"
    # Sarvam's codemix mode often tags Indian-accented English as hi-IN, so the code alone never makes a Roman
    # sentence Hinglish: "i want to send this photo to keshav" stays English. It still tips one Hindi word over.
    if (language_code or "").lower().startswith("hi") and (strong + weak) >= 1:
        return "hinglish"
    return "en"


def _from_code(language_code: str | None) -> str:
    return "hi" if (language_code or "").lower().startswith("hi") else "en"


def sarvam_language_hint(style: str | None) -> str:
    return {"en": "en-IN", "hi": "hi-IN", "hinglish": "hi-IN"}.get(style or "", "unknown")
