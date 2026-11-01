"""Arjun's bill splitter: divides a bill, with an optional tip, and says each share rounded up to the rupee.

Compiled from 6 conversations the LLM handled ("split 1840 between 4", "split 2350 among me and 3 friends with 10
percent tip"). No state: the same question always gets the same answer.
"""

import math
import re

WORDS = {"two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8}


def run(job):
    text = job["text"].lower()
    amount = re.search(r"(\d[\d,]*(?:\.\d+)?)", text)
    if not amount:
        return {"say": "Tell me the bill and how many people, like: split 1840 between 4.", "state": {}, "urgent": False}
    total = float(amount.group(1).replace(",", ""))
    rest = text[amount.end():]
    people = 0
    m = re.search(r"(?:between|among|by|in)\s+(?:me and\s+)?(\d+|" + "|".join(WORDS) + ")", rest)
    if m:
        people = int(m.group(1)) if m.group(1).isdigit() else WORDS[m.group(1)]
        if "me and" in rest[m.start(): m.end()]:
            people += 1
    if people < 2:
        return {"say": "How many people should I split " + str(int(total)) + " rupees between?", "state": {},
                "urgent": False}
    tip = re.search(r"(\d{1,2})\s*(?:%|percent)\s*tip", rest)
    tip_pct = int(tip.group(1)) if tip else 0
    with_tip = total * (1 + tip_pct / 100)
    share = math.ceil(with_tip / people)
    say = "Each of the " + str(people) + " people pays " + str(share) + " rupees."
    if tip_pct:
        say += " That includes a " + str(tip_pct) + " percent tip; the total is " + str(math.ceil(with_tip)) + " rupees."
    return {"say": say, "state": {}, "urgent": False}
