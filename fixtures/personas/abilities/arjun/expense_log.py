"""Arjun's spoken expense log: records what he paid and totals it by day, week, month or category.

Compiled from 14 conversations the LLM handled ("spent 240 on groceries", "how much did I spend this week",
"what did transport cost this month"). Arjun is blind, so every number is read out in full and nothing refers to
the screen.
"""

import re
from datetime import date, timedelta

CATEGORIES = {
    "groceries": r"grocer|vegetable|sabzi|milk|kirana|fruit|bigbasket|blinkit",
    "transport": r"uber|ola|cab|auto|metro|bus|rapido|fuel|petrol",
    "food": r"swiggy|zomato|lunch|dinner|restaurant|coffee|tea|snack",
    "bills": r"electricity|bill|recharge|internet|wifi|gas|rent|phone",
    "health": r"medicine|pharmacy|chemist|doctor|test|lab",
}


def _category(text):
    for name in CATEGORIES:
        if name.rstrip("s") in text or re.search(CATEGORIES[name], text):
            return name
    return "other"


def _since(text, today):
    end = date.fromisoformat(today)
    if "today" in text:
        return end, "today"
    if "yesterday" in text:
        return end - timedelta(days=1), "since yesterday"
    if "month" in text:
        return end.replace(day=1), "this month"
    return end - timedelta(days=end.weekday()), "this week"


def _rupees(n):
    return str(int(n)) + " rupees" if n == int(n) else str(n) + " rupees"


def run(job):
    text = job["text"].lower()
    today = job["today"]
    state = dict(job.get("state") or {})
    entries = list(state.get("entries") or [])
    paid = re.search(r"(?:spent|paid|gave|spend|bought)\D{0,20}?(\d[\d,]*(?:\.\d+)?)", text)
    if paid and not re.search(r"how much|total|what did", text):
        amount = float(paid.group(1).replace(",", ""))
        cat = _category(text)
        entries.append({"date": today, "amount": amount, "category": cat})
        state["entries"] = entries[-500:]
        week_start = date.fromisoformat(today) - timedelta(days=date.fromisoformat(today).weekday())
        week = sum(e["amount"] for e in entries if date.fromisoformat(e["date"]) >= week_start)
        return {"say": "Noted " + _rupees(amount) + " for " + cat + ". This week so far: " + _rupees(week) + ".",
                "state": state, "urgent": False}
    start, label = _since(text, today)
    cat = _category(text)
    picked = [e for e in entries if date.fromisoformat(e["date"]) >= start and (cat == "other" or e["category"] == cat)]
    total = sum(e["amount"] for e in picked)
    what = "on " + cat + " " if cat != "other" else ""
    say = "You spent " + _rupees(total) + " " + what + label + ", in " + str(len(picked)) + " payments."
    if cat == "other" and picked:
        by = {}
        for e in picked:
            by[e["category"]] = by.get(e["category"], 0) + e["amount"]
        top = sorted(by.items(), key=_second, reverse=True)[:2]
        say += " Most went to " + " and ".join(k + ", " + _rupees(v) for k, v in top) + "."
    return {"say": say, "state": state, "urgent": False}


def _second(pair):
    return pair[1]
