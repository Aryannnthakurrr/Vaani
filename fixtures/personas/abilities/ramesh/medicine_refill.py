"""Ramesh's medicine stock: counts down his tablets day by day and says when to reorder.

Compiled from 7 conversations the LLM handled ("metformin ki kitni goli bachi", "dawai kab khatam hogi",
"aaj 60 goli ka patta aaya"). Reorder is due 5 days before the last tablet, so Amit has time to send it.
"""

import re
from datetime import date, timedelta

NAMES = {"metformin": r"metformin|मेटफ़ॉर्मिन|मेटफॉर्मिन|sugar ki goli|शुगर की गोली", "glimepiride": r"glimepiride|ग्लिमे"}
REORDER_DAYS = 5


def _which(text, meds):
    t = text.lower()
    for name in meds:
        if re.search(NAMES.get(name, name), t):
            return name
    return ""


def _left(med, today):
    since = (date.fromisoformat(today) - date.fromisoformat(med["as_of"])).days
    return max(0, med["left"] - since * med["per_day"])


def _line(name, med, today):
    left = _left(med, today)
    days = left // med["per_day"] if med["per_day"] else 0
    runs_out = date.fromisoformat(today) + timedelta(days=days)
    reorder = runs_out - timedelta(days=REORDER_DAYS)
    line = med["label"] + " की " + str(left) + " गोलियाँ बची हैं, " + str(days) + " दिन की।"
    if days <= REORDER_DAYS:
        return line + " अभी मँगवा लीजिए, " + runs_out.strftime("%d/%m") + " को ख़त्म होंगी।"
    return line + " " + reorder.strftime("%d/%m") + " तक दोबारा मँगवा लीजिए।"


def run(job):
    text = job["text"]
    today = job["today"]
    state = dict(job.get("state") or {})
    meds = dict(state.get("medicines") or {})
    if not meds:
        return {"say": "अभी कोई दवा दर्ज नहीं है।", "state": state, "urgent": False}
    name = _which(text, meds)
    count = re.search(r"(?<!\d)(\d{1,3})(?!\d)\s*(goli|गोली|गोलियाँ|tablet|tab)", text.lower())
    if name and count:
        med = dict(meds[name])
        added = re.search(r"aaya|aayi|आया|आई|new|naya|नया|patta|पत्ता|mila|मिला", text.lower())
        med["left"] = (_left(med, today) if added else 0) + int(count.group(1))
        med["as_of"] = today
        meds[name] = med
        state["medicines"] = meds
        return {"say": "ठीक है। " + _line(name, med, today), "state": state, "urgent": False}
    names = [name] if name else list(meds)
    lines = [_line(n, meds[n], today) for n in names]
    return {"say": " ".join(lines), "state": state, "urgent": False}
