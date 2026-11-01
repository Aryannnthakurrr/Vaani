"""Sunita's BP diary: logs a reading, grades it against Dr. Gupta's target, and keeps a 7-day average.

Compiled from 13 conversations the LLM handled ("BP 146 by 92 aaya", "upar 150 neeche 95", "is hafte BP kaisa
raha"). Hinglish, short sentences, as Sunita likes. A reading of 180/120 or more is always urgent.
"""

import re
from datetime import date


def _reading(text):
    t = text.lower()
    m = re.search(r"(\d{2,3})\s*(?:/|by|over|bata|batey|and|aur|-)\s*(\d{2,3})", t)
    if not m:
        m = re.search(r"(?:upar|upper|systolic)\D{0,8}(\d{2,3}).{0,20}?(?:neeche|niche|lower|diastolic)\D{0,8}(\d{2,3})", t)
    if not m:
        return None
    sys_, dia = int(m.group(1)), int(m.group(2))
    if not (70 <= sys_ <= 260 and 40 <= dia <= 160 and sys_ > dia):
        return None
    pulse = re.search(r"(?:pulse|nabz|dhadkan)\D{0,6}(\d{2,3})", t)
    return sys_, dia, int(pulse.group(1)) if pulse else None


def _grade(s, d):
    if s >= 180 or d >= 120:
        return "crisis"
    if s >= 140 or d >= 90:
        return "stage2"
    if s >= 130 or d >= 80:
        return "stage1"
    if s >= 120:
        return "elevated"
    return "normal"


WORDS = {
    "crisis": "bahut zyada hai",
    "stage2": "high hai",
    "stage1": "thoda high hai",
    "elevated": "thoda upar hai, par theek",
    "normal": "bilkul theek hai",
}


def _week(readings, today):
    end = date.fromisoformat(today)
    recent = [r for r in readings if (end - date.fromisoformat(r["date"])).days < 7]
    if not recent:
        return None
    s = round(sum(r["sys"] for r in recent) / len(recent))
    d = round(sum(r["dia"] for r in recent) / len(recent))
    return s, d, len(recent)


def run(job):
    state = dict(job.get("state") or {})
    readings = list(state.get("readings") or [])
    target = state.get("target") or [130, 80]
    got = _reading(job["text"])
    if got is None:
        week = _week(readings, job["today"])
        if week is None:
            return {"say": "Is hafte koi BP reading nahi hai. Check karke boliye, jaise '130 by 85'.", "state": state,
                    "urgent": False}
        return {"say": "Is hafte " + str(week[2]) + " readings, average " + str(week[0]) + " by " + str(week[1])
                + ". Target " + str(target[0]) + " by " + str(target[1]) + " hai.", "state": state, "urgent": False}
    s, d, pulse = got
    readings.append({"date": job["today"], "sys": s, "dia": d, "pulse": pulse})
    state["readings"] = readings[-120:]
    grade = _grade(s, d)
    say = "BP " + str(s) + " by " + str(d) + " " + WORDS[grade] + "."
    urgent = grade == "crisis"
    if urgent:
        say += (" Abhi aaraam se baithiye aur 5 minute baad dobara check kijiye. Seene mein dard, saans phoolna ya"
                " chakkar ho to turant 108 ya Priya ko call kijiye.")
    elif s > target[0] or d > target[1]:
        say += " Dr. Gupta ka target " + str(target[0]) + " by " + str(target[1]) + " hai."
    week = _week(readings, job["today"])
    if week and week[2] > 1 and not urgent:
        say += " Hafte ka average " + str(week[0]) + " by " + str(week[1]) + "."
    return {"say": say + " Likh liya.", "state": state, "urgent": urgent}
