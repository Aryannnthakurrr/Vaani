"""Sunita's salt budget: adds up the salt in what she ate today against a 5 g day (WHO), with a swap tip.

Compiled from 9 conversations the LLM handled ("aaj do paratha aur achaar khaya", "kitna namak ho gaya aaj").
The LLM's per-serving estimates became this fixed table; anything not in it is left to the LLM next time.
"""

import re

# Grams of salt per usual serving (Indian home portions; the LLM's estimates, frozen).
SALT_G = {
    "achaar": (r"achaar|achar|pickle", 1.5, "nimbu ya pudina chutney"),
    "papad": (r"papad", 1.0, "bhuna chana"),
    "namkeen": (r"namkeen|bhujia|mixture", 1.0, "bhuna makhana"),
    "chips": (r"chips|wafers|kurkure", 1.2, "ghar ke bhune chane"),
    "maggi": (r"maggi|noodles", 2.8, "ghar ka poha"),
    "pav bhaji": (r"pav bhaji", 2.5, "ghar ki sabzi roti"),
    "samosa": (r"samosa|kachori", 0.9, "dhokla"),
    "pakoda": (r"pakod|pakor|bhajiya", 1.0, "bhuna paneer"),
    "chole": (r"chole|rajma", 1.5, ""),
    "dal": (r"\bdal\b|daal", 1.0, ""),
    "sabzi": (r"sabzi|sabji", 1.0, ""),
    "paratha": (r"paratha|parantha|parotha", 0.6, "saada roti"),
    "poha": (r"poha|upma", 1.0, ""),
    "raita": (r"raita|dahi", 0.4, ""),
    "roti": (r"roti|chapati|phulka", 0.1, ""),
    "chawal": (r"chawal|\brice", 0.1, ""),
}
NUMBERS = {"ek": 1, "do": 2, "teen": 3, "char": 4, "chaar": 4, "paanch": 5, "aadha": 0.5, "half": 0.5}
LIMIT_G = 5.0


def _count(before):
    words = before.split()[-2:]
    for w in reversed(words):
        if w.isdigit():
            return min(int(w), 10)
        if w in NUMBERS:
            return NUMBERS[w]
    return 1


def run(job):
    text = job["text"].lower()
    state = dict(job.get("state") or {})
    if state.get("date") != job["today"]:
        state = {"date": job["today"], "grams": 0.0, "items": []}
    added = 0.0
    names = []
    tip = ""
    for name in SALT_G:
        pattern, grams, swap = SALT_G[name]
        m = re.search(pattern, text)
        if m:
            n = _count(text[: m.start()])
            added += grams * n
            names.append(name)
            if grams >= 1.0 and swap and not tip:
                tip = " " + name.capitalize() + " ki jagah " + swap + " try kijiye."
    total = round(state.get("grams", 0.0) + added, 1)
    state["grams"] = total
    state["items"] = list(state.get("items") or []) + names
    left = round(LIMIT_G - total, 1)
    if not names:
        say = "Aaj ab tak lagbhag " + str(total) + " gram namak hua hai, " + str(LIMIT_G) + " gram mein se."
    else:
        say = ", ".join(names) + ": lagbhag " + str(round(added, 1)) + " gram namak. Aaj ka total " + str(total) + " gram."
    if left < 0:
        say += " Aaj ki limit paar ho gayi, ab baaki din bina namak ka khaana rakhiye." + tip
    elif left < 1.5:
        say += " Sirf " + str(left) + " gram bacha hai." + tip
    elif names:
        say += " Abhi " + str(left) + " gram ki gunjaish hai." + tip
    return {"say": say, "state": state, "urgent": False}
