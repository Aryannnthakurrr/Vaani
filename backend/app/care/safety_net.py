"""Rule-based red-flag safety net (workflow §3.4). Deterministic, no LLM: any hit forces the emergency path.

Two entry points: `scan(text)` for free speech ("he passed out", "seene mein dard aur paseena") and
`scan_answer(question, answer)` for a yes/no reply to a follow-up ("Is she breathing normally?" → "nahi").
Simple negations ("no sweating", "paseena nahi hai") are removed first so they don't trigger a flag.
"""

from __future__ import annotations

import re

LABELS = {
    "unresponsive": "Unresponsive or unconscious",
    "not_breathing": "Not breathing or severe breathlessness",
    "cardiac_pattern": "Chest pain with warning signs",
    "stroke": "Possible stroke (FAST)",
    "severe_bleeding": "Severe bleeding",
    "seizure": "Seizure",
    "major_trauma": "Major trauma",
    "anaphylaxis": "Severe allergic reaction",
    "poisoning": "Poisoning or overdose",
    "pregnancy_emergency": "Pregnancy emergency",
    "self_harm": "Self-harm risk",
}

_I = re.IGNORECASE
_SIMPLE = {
    "unresponsive": r"not respond|unresponsive|passed out|unconscious|not waking|won'?t wake|behosh|बेहोश|hosh nahi|होश नहीं",
    "not_breathing": r"not breathing|can'?t breathe|cannot breathe|can not breathe|unable to breathe|gasping|"
    r"struggling to breathe|lips (?:are |have )?(?:turned )?blue|blue lips|saa?ns nahi|साँस नहीं|सांस नहीं|"
    r"saa?ns (?:lene mein|nahi aa)|dam ghut",
    "stroke": r"face (?:is )?droop|drooping face|droopy face|slurred|speech is slurred|can'?t speak properly|"
    r"arm (?:is )?weak|weak arm|one side (?:is )?weak|weakness on one side|sudden(?:ly)? confused|sudden confusion|"
    r"mu(?:n)?h (?:tedha|tehda)|लकवा|lakwa|paraly[sz]|zubaa?n ladkhada|ladkhada",
    "severe_bleeding": r"won'?t stop bleeding|not stop(?:ping)? bleeding|bleeding (?:won'?t|doesn'?t|does not) stop|"
    r"lots? of blood|spurting|heavy bleeding|bleeding heavily|bahut khoon|khoon (?:nahi|nahin) ruk|"
    r"खून नहीं रुक|बहुत खून",
    "seizure": r"seizure|convuls|\bfits?\b|fit aa|\bdaura\b|दौरा|mirgi|मिर्गी",
    "major_trauma": r"road accident|\baccident\b|fell from (?:a |the )?(?:height|roof|terrace|stairs|ladder)|"
    r"fall from height|head injury|hit (?:his|her|my|their) head|sa?r (?:pe|par|mein) chot|सिर (?:पर|में) चोट|"
    r"chhat se gir|छत से गिर",
    "anaphylaxis": r"throat (?:is )?(?:swell|closing|tight)|can'?t swallow|cannot swallow|tongue (?:is )?swell|"
    r"gala sooj|gala suj|गला सूज",
    "poisoning": r"swallowed|poison|overdose|pesticide|too many (?:pills|tablets)|ze?h[ae]r|ज़हर|जहर|keetnashak|"
    r"कीटनाशक",
    "self_harm": r"kill (?:my|him|her)self|suicid|end (?:my|his|her) life|want to die|don'?t want to live|"
    r"marna chaht|jaan de(?:na|ni|ne)|aatmahatya|आत्महत्या|खुद को मार",
}
_CHEST = (
    r"chest pain|pain in (?:my |the |his |her )?chest|chest (?:is )?tight|tightness in (?:the )?chest|"
    r"seene? (?:mein|me|par) dard|chhati (?:mein|me) dard|सीने में दर्द|छाती में दर्द"
)
_CARDIAC_WITH = (
    r"sweat|paseen|पसीना|\barm\b|\bhaath\b|बांह|\bjaw\b|jabd|\bback\b|peeth|पीठ|breathless|"
    r"short of breath|saa?ns|साँस|सांस|vomit|ulti"
)
_PREGNANT = r"pregnan|garbh|गर्भ|expecting a baby|pet se hai"
_PREGNANCY_DANGER = (
    r"bleed|khoon|खून|seizure|\bfits?\b|daura|severe (?:abdominal|stomach|belly) pain|"
    r"pet (?:mein|me) (?:bahut |tez )?dard"
)

_NEG_BEFORE = re.compile(r"\b(?:no|without|denies|never had|not having)\s+(?:\w+\s+){0,2}?(\w+)", _I)
_NEG_AFTER = re.compile(r"(\w+)\s+(?:\w+\s+)?(?:nahi|nahin|नहीं)\s+(?:hai|ho raha|hua|हो रहा|है)", _I)
# Words a negation may cancel. "Not breathing" and "can't breathe" stay positive on purpose.
_NEGATABLE = re.compile(r"sweat|paseen|पसीना|vomit|ulti|chest|seene|arm|jaw|back|bleed|khoon|fit|seizure", _I)


def _strip_negations(text: str) -> str:
    def cut(m: re.Match[str]) -> str:
        return " " if _NEGATABLE.search(m.group(1)) else m.group(0)

    return _NEG_AFTER.sub(cut, _NEG_BEFORE.sub(cut, text))


def scan(text: str) -> list[str]:
    """Red-flag ids found in free speech, in a stable order."""
    t = _strip_negations(text or "")
    found = [flag for flag, pattern in _SIMPLE.items() if re.search(pattern, t, _I)]
    if re.search(_CHEST, t, _I) and re.search(_CARDIAC_WITH, t, _I):
        found.append("cardiac_pattern")
    if re.search(_PREGNANT, t, _I) and re.search(_PREGNANCY_DANGER, t, _I):
        found.append("pregnancy_emergency")
    return sorted(set(found), key=list(LABELS).index)


_YES = re.compile(r"^\s*(?:yes|yeah|yep|haa?n?|ji(?: haa?n)?|hai|ho raha|hmm|हाँ|हां|जी)\b", _I)
_NO = re.compile(r"^\s*(?:no|nope|not|nahi|nahin|na|nai|नहीं|ना)\b", _I)

# (topic in the question, flag, answer that means danger)
_QUESTION_RULES: list[tuple[str, str, str]] = [
    (r"conscious|responding|awake|able to talk|hosh", "unresponsive", "no"),
    (r"breathing normally|breathe normally|saa?ns (?:theek|normal)", "not_breathing", "no"),
    (
        r"trouble breathing|difficulty breathing|short of breath|breathless|saa?ns (?:lene mein|phool)",
        "not_breathing",
        "yes",
    ),
    # Stroke before cardiac: "arm weakness" is a stroke sign, "pain spreading to the arm" a cardiac one.
    (r"droop|slurred|weakness|one side|lakwa|confus|tedha|kamzori", "stroke", "yes"),
    (r"spreading|jaw|jabd|sweat|paseen", "cardiac_pattern", "yes"),
    (r"bleeding|khoon", "severe_bleeding", "yes"),
    (r"seizure|fits?\b|daura", "seizure", "yes"),
    (r"swallow|throat|gala", "anaphylaxis", "yes"),
    (r"poison|overdose|swallowed|zeh", "poisoning", "yes"),
    (r"harm (?:yourself|themsel|himself|herself)|suicid|end (?:your|their) life", "self_harm", "yes"),
]


def scan_answer(question: str, answer: str) -> list[str]:
    """Red flags from a follow-up answer: free-speech hits plus a yes/no read against the question's topic."""
    found = set(scan(answer))
    said = "yes" if _YES.search(answer or "") else "no" if _NO.search(answer or "") else None
    if said:
        for topic, flag, danger in _QUESTION_RULES:
            if re.search(topic, question or "", _I) and said == danger:
                found.add(flag)
                break
    return sorted(found, key=list(LABELS).index)
