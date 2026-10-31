"""Turns a call transcript into structured answers: B4 (inquiry → CallResult) and B6 (booking → BookingResult).

A model fills the schema when available; the rule-based readers below cover mock mode and model failures. Both
only read what the hospital said: the transcript is data, never instructions.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from app.care.models import BookingResult, CallResult, Hospital, Slot
from app.llm.base import LLMClient
from app.llm.prompts import render

IST = ZoneInfo("Asia/Kolkata")
_I = re.IGNORECASE
_DAYS = {
    "today": 0, "aaj": 0, "आज": 0,
    "tomorrow": 1, "kal": 1, "कल": 1,
    "day after tomorrow": 2, "parson": 2, "parso": 2, "परसों": 2,
}  # fmt: skip
_WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
_DAY_RE = re.compile(
    r"day after tomorrow|today|tomorrow|aaj|kal|parso?n?|आज|कल|परसों|" + "|".join(_WEEKDAYS), _I
)
_TIME_RE = re.compile(r"\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|baje|बजे)?", _I)


def _hour24(hour: int, minute: int, suffix: str) -> tuple[int, int] | None:
    s = suffix.lower().replace(".", "")
    if not (0 <= minute < 60) or hour > 23:
        return None
    if s == "pm" and hour < 12:
        hour += 12
    elif s == "am" and hour == 12:
        hour = 0
    elif s in ("baje", "बजे", "") and 1 <= hour <= 7:
        hour += 12  # "4 baje" at a clinic means the afternoon
    return hour, minute


def parse_slots(text: str, now: datetime | None = None) -> list[Slot]:
    """Times like "kal 11 baje", "tomorrow at 4:30 pm" or "Monday 10 am", each tied to the day word before it."""
    now = now or datetime.now(IST)
    slots: list[Slot] = []
    day_offset: int | None = None
    pos = 0
    for m in _TIME_RE.finditer(text or ""):
        if not m.group(3) and not m.group(2):
            continue  # a bare number ("2 doctors") is not a time
        before = text[pos : m.start()]
        days = list(_DAY_RE.finditer(before))
        if days:
            word = days[-1].group(0).lower()
            if word in _WEEKDAYS:
                day_offset = (_WEEKDAYS.index(word) - now.weekday()) % 7 or 7
            else:
                day_offset = _DAYS.get(word, day_offset)
        pos = m.end()
        hm = _hour24(int(m.group(1)), int(m.group(2) or 0), m.group(3) or "")
        if not hm:
            continue
        offset = day_offset if day_offset is not None else 0
        start = (now + timedelta(days=offset)).replace(hour=hm[0], minute=hm[1], second=0, microsecond=0)
        if day_offset is None and start <= now:
            start += timedelta(days=1)
        slot = Slot(start=start.isoformat(), end=(start + timedelta(minutes=15)).isoformat())
        if slot not in slots:
            slots.append(slot)
    return slots[:6]


def _clinic_text(transcript: list[dict[str, str]]) -> str:
    return " ".join(line["text"] for line in transcript if line.get("role") == "clinic")


def _doctor(text: str) -> str | None:
    # The title matches in any case; the name must be capitalised so "doctor is not available" isn't a name.
    m = re.search(r"\b(?i:dr|doctor)\.?\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)", text)
    return f"Dr. {m.group(1)}" if m else None


def _fee(text: str) -> int | None:
    m = re.search(r"(?:₹|rs\.?|inr)\s*(\d{2,5})|(\d{2,5})\s*(?:rupees|rupaye|रुपये|rs)", text, _I)
    return int(m.group(1) or m.group(2)) if m else None


def rule_inquiry(hospital: Hospital, transcript: list[dict[str, str]], call_sid: str | None) -> CallResult:
    said = _clinic_text(transcript)
    lower = said.lower()
    no = re.search(r"\b(no|not available|nahi|nahin|नहीं|unavailable|on leave|chutti)\b", lower)
    slots = parse_slots(said)
    available = True if slots else (False if no else None)
    return CallResult(
        hospital_id=hospital.id,
        hospital_name=hospital.name,
        call_sid=call_sid,
        call_outcome="COMPLETED" if said else "NO_ANSWER",
        specialty_available=available,
        doctor_name=_doctor(said),
        slots=slots,
        fee_inr=_fee(said),
        walk_in_ok=True if re.search(r"walk.?in|seedhe aa|direct aa", lower) else None,
        insurance="mentioned" if re.search(r"ayushman|insurance|cashless|bima", lower) else None,
        eta_minutes=hospital.eta_minutes,
        address=hospital.address,
        confidence="low",
    )


async def inquiry_result(
    llm: LLMClient, hospital: Hospital, specialty: str, transcript: list[dict[str, str]], call_sid: str | None
) -> CallResult:
    rules = rule_inquiry(hospital, transcript, call_sid)
    if not _clinic_text(transcript):
        return rules
    system = render("care_extract", mode="inquiry", specialty=specialty, now=datetime.now(IST).isoformat())
    out = await llm.structured("care_extract", system, json.dumps(transcript, ensure_ascii=False), CallResult)
    if not isinstance(out, CallResult):
        return rules
    # Identity and logistics come from our side, never from the model.
    return out.model_copy(
        update={
            "hospital_id": hospital.id,
            "hospital_name": hospital.name,
            "call_sid": call_sid,
            "call_outcome": out.call_outcome if out.call_outcome != "NOT_ALLOWED" else "COMPLETED",
            "eta_minutes": hospital.eta_minutes,
            "address": hospital.address,
        }
    )


def rule_booking(transcript: list[dict[str, str]], wanted: Slot) -> BookingResult:
    said = _clinic_text(transcript)
    lower = said.lower()
    ref = re.search(r"(?:booking|token|reference|ref|appointment)\s*(?:number|no\.?|id)?\s*(?:is|hai)?\s*:?\s*"
                    r"([A-Z0-9][A-Z0-9-]{1,11})", said, _I)  # fmt: skip
    # "booked" alone is ambiguous ("11 is already booked"), so only "is booked" / "booked for" count.
    confirmed = re.search(r"\b(confirm(?:ed)?|done|ho gaya|ho jayega|fixed|is booked|booked for)\b", lower)
    unavailable = re.search(r"(not available|already booked|full|nahi milega|khali nahi|no slot)", lower)
    if unavailable and not confirmed:
        alternatives = [s for s in parse_slots(said) if s.start != wanted.start]
        return BookingResult(status="slot_unavailable", alternatives=alternatives)
    if confirmed or ref:
        return BookingResult(
            status="confirmed",
            doctor_name=_doctor(said),
            start_iso=wanted.start,
            end_iso=wanted.end,
            booking_ref=ref.group(1) if ref else None,
        )
    return BookingResult(status="failed")


async def booking_result(llm: LLMClient, transcript: list[dict[str, str]], wanted: Slot) -> BookingResult:
    rules = rule_booking(transcript, wanted)
    if not _clinic_text(transcript):
        return rules
    system = render("care_extract", mode="booking", specialty="", now=datetime.now(IST).isoformat())
    prompt = json.dumps({"wanted_slot": wanted.model_dump(), "transcript": transcript}, ensure_ascii=False)
    out = await llm.structured("care_extract", system, prompt, BookingResult)
    return out if isinstance(out, BookingResult) else rules
