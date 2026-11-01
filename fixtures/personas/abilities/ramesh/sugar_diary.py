"""Ramesh's sugar diary: logs a glucometer reading, checks it against Dr. Kapoor's targets, says the trend.

Compiled from 11 conversations the LLM handled ("aaj fasting 148 aaya", "khane ke baad 210 tha", "pichhle hafte
sugar kaisi rahi"). Speaks Hindi, the way Ramesh asks.
"""

import re

DEFAULT_TARGETS = {"fasting": [80, 130], "after_food": [70, 180], "random": [70, 180]}
LABEL = {"fasting": "खाली पेट", "after_food": "खाने के बाद", "random": "अभी की"}


def _kind(text):
    t = text.lower()
    if re.search(r"fasting|khali pet|खाली पेट|nahar|निहार|subah utha", t):
        return "fasting"
    if re.search(r"khane ke baad|खाने के बाद|after (food|meal|lunch|dinner)|\bpp\b|baad mein|बाद में", t):
        return "after_food"
    return "random"


def _readings(text):
    found = []
    for n in re.findall(r"(?<!\d)(\d{2,3})(?!\d)", text):
        value = int(n)
        if 20 <= value <= 600:
            found.append(value)
    return found


def _summary(readings):
    if not readings:
        return "अभी तक कोई शुगर रीडिंग दर्ज नहीं है। जाँच के बाद बोलिए, जैसे 'खाली पेट 120 आई'।"
    last = readings[-3:]
    parts = []
    for r in last:
        parts.append(LABEL.get(r["kind"], "") + " " + str(r["value"]) + " (" + r["date"][5:] + ")")
    return "पिछली " + str(len(last)) + " रीडिंग: " + ", ".join(parts) + "।"


def run(job):
    text = job["text"]
    state = dict(job.get("state") or {})
    readings = list(state.get("readings") or [])
    targets = state.get("targets") or DEFAULT_TARGETS
    values = _readings(text)
    if not values:
        return {"say": _summary(readings), "state": state, "urgent": False}
    value = values[0]
    kind = _kind(text)
    low, high = targets.get(kind, DEFAULT_TARGETS[kind])
    earlier = [r["value"] for r in readings if r["kind"] == kind][-3:]
    readings.append({"date": job["today"], "kind": kind, "value": value})
    state["readings"] = readings[-90:]
    urgent = False
    if value < 70:
        urgent = True
        say = (
            "शुगर " + str(value) + " बहुत कम है। अभी एक चम्मच चीनी या ग्लूकोज़ लीजिए, 15 मिनट बाद फिर जाँचिए। "
            "ठीक न लगे तो अमित को फ़ोन कीजिए।"
        )
    elif value >= 300:
        urgent = True
        say = "शुगर " + str(value) + " बहुत ज़्यादा है। आज ही डॉ. कपूर से बात कीजिए, और पानी पीते रहिए।"
    elif value > high:
        say = LABEL[kind] + " शुगर " + str(value) + " है, डॉ. कपूर के लक्ष्य " + str(high) + " से ज़्यादा।"
    elif value < low:
        say = LABEL[kind] + " शुगर " + str(value) + " है, लक्ष्य से थोड़ी कम। कुछ हल्का खा लीजिए।"
    else:
        say = LABEL[kind] + " शुगर " + str(value) + " ठीक है, आपके लक्ष्य के अंदर।"
    if earlier and not urgent:
        avg = round(sum(earlier) / len(earlier))
        trend = "बेहतर" if value < avg - 5 else ("ज़्यादा" if value > avg + 5 else "लगभग वैसी ही")
        say += " पिछली " + str(len(earlier)) + " बार का औसत " + str(avg) + " था, आज " + trend + "।"
    return {"say": say + " लिख लिया।", "state": state, "urgent": urgent}
