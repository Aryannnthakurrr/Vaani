"""Filter and rank hospitals before calling (B2), and re-rank on confirmed answers after calling (B5)."""

from __future__ import annotations

import json
import math
import os
import re
from datetime import datetime

from pydantic import BaseModel

from app.care.models import CallResult, Hospital, Slot
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


def filter_candidates(hospitals: list[Hospital], specialty: str) -> list[Hospital]:
    """Drops places without a phone, places closed now (unless open 24 h) and clearly wrong types."""
    return [
        h
        for h in hospitals
        if h.phone and not (h.open_now is False and not h.open_24h) and not _wrong_type(h, specialty)
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


async def score_capability(llm: LLMClient, hospitals: list[Hospital], specialty: str) -> None:
    """One batched model call (0–1 per place); the name-based guess fills anything it doesn't answer."""
    for h in hospitals:
        h.capability = capability_guess(h, specialty)
    if not hospitals:
        return
    places = [{"id": h.id, "name": h.name, "types": h.types[:6]} for h in hospitals]
    out = await llm.structured(
        "care_capability", render("care_capability", specialty=specialty), json.dumps(places), _Scores
    )
    if isinstance(out, _Scores):
        by_id = {s.id: min(max(s.capability, 0.0), 1.0) for s in out.scores}
        for h in hospitals:
            h.capability = by_id.get(h.id, h.capability)


def rank(hospitals: list[Hospital]) -> list[Hospital]:
    """score = 0.45·capability + 0.35·(1 − eta/max_eta) + 0.20·(log reviews / log max reviews)·(rating/5)."""
    if not hospitals:
        return []
    max_eta = max((h.eta_minutes or 0.0) for h in hospitals) or 1.0
    max_reviews = math.log1p(max(h.rating_count for h in hospitals)) or 1.0
    for h in hospitals:
        eta = h.eta_minutes if h.eta_minutes is not None else max_eta
        reviews = math.log1p(h.rating_count) / max_reviews
        h.score = round(
            0.45 * h.capability + 0.35 * (1 - eta / max_eta) + 0.20 * reviews * ((h.rating or 0) / 5), 4
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


def rerank(results: list[CallResult], hospitals: dict[str, Hospital]) -> list[CallResult]:
    """Confirmed specialty first, then the earliest slot, then ETA, then fee, then rating."""
    usable = [
        r for r in results if r.call_outcome == "COMPLETED" and r.specialty_available is not False and r.slots
    ]

    def key(r: CallResult) -> tuple:
        slot = best_slot(r)
        h = hospitals.get(r.hospital_id)
        return (
            0 if r.specialty_available else 1,
            _when(slot) if slot else datetime.max,
            r.eta_minutes if r.eta_minutes is not None else 1e9,
            r.fee_inr if r.fee_inr is not None else 1e9,
            -((h.rating or 0) if h else 0),
        )

    return sorted(usable, key=key)
