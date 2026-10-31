"""Filter and rank hospitals before calling (B2), and re-rank on confirmed answers after calling (B5)."""

from __future__ import annotations

import json
import math
import os
import re
from datetime import datetime

from pydantic import BaseModel

from app.care.models import CallResult, Hospital, IntakeNote, Preferences, Slot
from app.llm.base import LLMClient
from app.llm.prompts import render
from app.telephony.calls import allowed_numbers, normalize_number

CALL_COUNT = 3
BACKUP_COUNT = 2
_I = re.IGNORECASE
_NARROW = {
    "dental": (r"dental|dentist|teeth|orthodont", {"dentist", "dental_clinic"}, "dent"),
    "eye": (r"\beye\b|optical|optician|netralaya|ophthalm|vision cent", set(), "ophthalm"),
    "lab": (r"diagnostic|patholog|\blabs?\b|imaging|scan cent|x-?ray", set(), "radiolog"),
}


def _wrong_type(h: Hospital, specialty: str) -> bool:
    for pattern, types, wanted in _NARROW.values():
        narrow = re.search(pattern, h.name, _I) or bool(types & set(h.types))
        if narrow and wanted not in specialty.lower():
            return True
    return False


def _named(h: Hospital, names: list[str]) -> bool:
    """True when one of the user's hospital names ("Max", "Saroj hospital") matches this place's name."""
    place = h.name.lower()
    for name in names:
        words = [w for w in re.findall(r"[a-z0-9]+", name.lower()) if w not in {"hospital", "clinic", "the"}]
        if words and all(w in place for w in words):
            return True
    return False


def filter_candidates(
    hospitals: list[Hospital], specialty: str, prefs: Preferences | None = None
) -> list[Hospital]:
    """Drops places without a phone, places closed now (unless open 24 h), clearly wrong types and any hospital the
    user said they don't want."""
    avoid = prefs.avoid if prefs else []
    return [
        h
        for h in hospitals
        if h.phone
        and not (h.open_now is False and not h.open_24h)
        and not _wrong_type(h, specialty)
        and not _named(h, avoid)
    ]


def capability_guess(h: Hospital, specialty: str) -> float:
    """Name-based estimate of an OPD for `specialty`, used when the model is unavailable."""
    name = h.name.lower()
    root = specialty.lower().split()[0][:6]
    if root and root in name:
        return 0.9
    if re.search(r"multi.?special|medical college|aiims|institute|general hospital", name):
        return 0.75
    if "hospital" in name or "hospital" in h.types:
        return 0.6
    return 0.5 if specialty == "general medicine" else 0.35


class _Score(BaseModel):
    id: str
    capability: float


class _Scores(BaseModel):
    scores: list[_Score]


def need_summary(note: IntakeNote) -> dict[str, object]:
    """What the capability scorer may know about the patient: the need, never the person."""
    return {
        "specialty": note.specialty,
        "required_facilities": note.required_facilities[:6],
        "possible_conditions": [c.name for c in note.possible_conditions[:3]],
        "acuity": note.emergency.acuity,
        "timeframe": note.appointment_timeframe or "next available",
        "doctor_gender": note.preferences.doctor_gender,
    }


async def score_capability(llm: LLMClient, hospitals: list[Hospital], note: IntakeNote) -> None:
    """One batched model call (0–1 per place) judged against the whole need, not just the specialty word; the
    name-based guess fills anything it doesn't answer."""
    for h in hospitals:
        h.capability = capability_guess(h, note.specialty)
    if not hospitals:
        return
    places = [{"id": h.id, "name": h.name, "types": h.types[:6]} for h in hospitals]
    need = json.dumps(need_summary(note), ensure_ascii=False)
    out = await llm.structured(
        "care_capability",
        render("care_capability", specialty=note.specialty, need=need),
        json.dumps(places),
        _Scores,
    )
    if isinstance(out, _Scores):
        by_id = {s.id: min(max(s.capability, 0.0), 1.0) for s in out.scores}
        for h in hospitals:
            h.capability = by_id.get(h.id, h.capability)


_URGENT = re.compile(
    r"today|aaj|आज|now|abhi|turant|urgent|jaldi|\b(?:[1-9]|1[0-9]|2[0-4]) ?(?:h|hours?|ghante)\b", _I
)
PREFERRED_BONUS = 0.15


def weights(note: IntakeNote | None) -> tuple[float, float, float]:
    """(capability, distance, reputation). Urgent needs favour the nearest capable place; routine ones the best."""
    if note is None:
        return 0.45, 0.35, 0.20
    if note.emergency.acuity <= 2 or _URGENT.search(note.appointment_timeframe or ""):
        return 0.40, 0.45, 0.15
    if note.emergency.acuity >= 4:
        return 0.50, 0.25, 0.25
    return 0.45, 0.35, 0.20


def rank(hospitals: list[Hospital], note: IntakeNote | None = None) -> list[Hospital]:
    """score = wc·capability + wd·(1 − eta/max_eta) + wr·(log reviews / log max reviews)·(rating/5), weights from
    the urgency (weights()), plus PREFERRED_BONUS for a hospital the user asked for by name."""
    if not hospitals:
        return []
    wc, wd, wr = weights(note)
    prefer = note.preferences.prefer if note else []
    max_eta = max((h.eta_minutes or 0.0) for h in hospitals) or 1.0
    max_reviews = math.log1p(max(h.rating_count for h in hospitals)) or 1.0
    for h in hospitals:
        eta = h.eta_minutes if h.eta_minutes is not None else max_eta
        reviews = math.log1p(h.rating_count) / max_reviews
        bonus = PREFERRED_BONUS if _named(h, prefer) else 0.0
        h.score = round(
            wc * h.capability + wd * (1 - eta / max_eta) + wr * reviews * ((h.rating or 0) / 5) + bonus, 4
        )
    return sorted(hospitals, key=lambda h: h.score, reverse=True)


def demo_numbers() -> tuple[dict[str, str], list[str]]:
    """DEMO_PHONE_MAP: a JSON object {place_id: number} or a JSON list / comma list used round-robin."""
    raw = os.environ.get("DEMO_PHONE_MAP", "").strip()
    if not raw:
        return {}, []
    try:
        data = json.loads(raw)
    except ValueError:
        data = [n for n in raw.split(",") if n.strip()]
    if isinstance(data, dict):
        return {str(k): normalize_number(str(v)) for k, v in data.items()}, []
    return {}, [normalize_number(str(n)) for n in data]


def default_demo_number() -> str | None:
    """The first number in CALL_ALLOWED_NUMBERS: with masking on it stands in for every hospital."""
    numbers = allowed_numbers()
    return numbers[0] if numbers else None


def mask_numbers() -> bool:
    """MASK_HOSPITAL_NUMBERS (default on): every hospital call rings a test number instead of the hospital."""
    return os.environ.get("MASK_HOSPITAL_NUMBERS", "1").strip().lower() not in {"0", "false", "no", "off"}


def assign_call_numbers(hospitals: list[Hospital], mask: bool) -> None:
    """The number each call dials. Masked: a teammate's from DEMO_PHONE_MAP, else the first allowed number, for
    every hospital (names and numbers on screen stay real). Unmasked: the hospital's own number, which is still
    only dialled if it is in CALL_ALLOWED_NUMBERS."""
    mapping, pool = demo_numbers() if mask else ({}, [])
    fallback = default_demo_number() if mask else None
    for i, h in enumerate(hospitals):
        if mask and (h.id in mapping or pool):
            h.call_phone = mapping.get(h.id) or pool[i % len(pool)]
        elif mask and fallback:
            h.call_phone = fallback
        else:
            h.call_phone = normalize_number(h.phone or "") or None


def split_calls(ranked: list[Hospital]) -> tuple[list[Hospital], list[Hospital]]:
    """Top three to call now, the next two as backups."""
    return ranked[:CALL_COUNT], ranked[CALL_COUNT : CALL_COUNT + BACKUP_COUNT]


# ------------------------------------------------------------------------------------------------- B5


def _when(slot: Slot) -> datetime:
    try:
        return datetime.fromisoformat(slot.start)
    except ValueError:
        return datetime.max


def best_slot(result: CallResult) -> Slot | None:
    return min(result.slots, key=_when) if result.slots else None


def rerank(
    results: list[CallResult], hospitals: dict[str, Hospital], prefs: Preferences | None = None
) -> list[CallResult]:
    """Confirmed specialty first; then anything that fails the user's needs (a needed facility missing, over their
    fee limit, their insurance refused) drops behind; then the earliest slot, ETA, fee and rating."""
    usable = [
        r for r in results if r.call_outcome == "COMPLETED" and r.specialty_available is not False and r.slots
    ]
    max_fee = prefs.max_fee_inr if prefs else None

    def misses(r: CallResult) -> int:
        return (
            (r.facilities_available is False)
            + (max_fee is not None and r.fee_inr is not None and r.fee_inr > max_fee)
            + (r.insurance_accepted is False)
        )

    def key(r: CallResult) -> tuple:
        slot = best_slot(r)
        h = hospitals.get(r.hospital_id)
        return (
            0 if r.specialty_available else 1,
            misses(r),
            _when(slot) if slot else datetime.max,
            r.eta_minutes if r.eta_minutes is not None else 1e9,
            r.fee_inr if r.fee_inr is not None else 1e9,
            -((h.rating or 0) if h else 0),
        )

    return sorted(usable, key=key)
