"""Path A: build the dispatch request from the intake note, call the mock ambulance API, relay tracking (§4 A2–A7).

The mock service (`mock_ambulance/`, port 9000) stands in for a private dispatch API; India's 108 has no public API.
The phone never talks to it: this module polls it and turns updates into care-session events, so swapping in a real
provider touches only this file.
"""

from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import Awaitable, Callable
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.care import safety_net
from app.care.session import CareSession

log = logging.getLogger("saathi.care.dispatch")
IST = ZoneInfo("Asia/Kolkata")
TERMINAL = {"CANCELLED", "COMPLETED"}
TRACK_LIMIT_S = 45 * 60
_CATEGORY = {
    "cardiac_pattern": "CARDIAC", "stroke": "STROKE", "not_breathing": "BREATHING", "unresponsive": "UNCONSCIOUS",
    "severe_bleeding": "HAEMORRHAGE", "seizure": "SEIZURE", "major_trauma": "TRAUMA", "anaphylaxis": "ALLERGY",
    "poisoning": "POISONING", "pregnancy_emergency": "OBSTETRIC", "self_harm": "PSYCHIATRIC",
}  # fmt: skip
_ALS = {"CARDIAC", "STROKE", "BREATHING", "TRAUMA", "UNCONSCIOUS"}

Notify = Callable[[str], Awaitable[bool]]  # True when at least one family member got it


def base_url() -> str:
    return os.environ.get("MOCK_AMBULANCE_BASE_URL", "http://localhost:9000").rstrip("/")


def api_key() -> str:
    return os.environ.get("MOCK_AMBULANCE_API_KEY", "dev-key")


def countdown_sec() -> int:
    try:
        return max(5, int(os.environ.get("EMERGENCY_COUNTDOWN_SEC", "15")))
    except ValueError:
        return 15


def priority(acuity: int) -> str:
    """Acuity 1→P1, 2→P2, 3→P3, 4–5→P4."""
    return {1: "P1", 2: "P2", 3: "P3"}.get(acuity, "P4")


def build_request(session: CareSession, landmark: str | None = None) -> dict[str, Any]:
    note = session.note
    assert note is not None
    loc = session.location
    p = note.patient if note.patient.age or note.patient.sex else session.patient
    flags = session.safety_flags or [r.flag for r in note.red_flags if r.status == "present"]
    category = next((_CATEGORY[f] for f in flags if f in _CATEGORY), "MEDICAL")
    prio = priority(note.emergency.acuity)
    age_sex = f"{p.age or '?'}{p.sex or ''}"
    signs = [safety_net.LABELS.get(f, f) for f in flags] + note.associated_symptoms
    if note.hpi.severity is not None:
        signs.append(f"pain {note.hpi.severity}/10")
    top = note.possible_conditions[0].name if note.possible_conditions else note.chief_complaint
    return {
        "external_ref": session.id,
        "requested_at": datetime.now(IST).isoformat(timespec="seconds"),
        "priority": prio,
        "service_level": "ALS" if prio in ("P1", "P2") and category in _ALS else "BLS",
        "incident": {"type": "MEDICAL", "category": category, "description": f"Suspected {top}"[:200]},
        "location": {
            "lat": loc.lat if loc else None,
            "lng": loc.lng if loc else None,
            "accuracy_m": loc.accuracy_m if loc else None,
            "address": (loc.address if loc else None) or "unknown",
            "landmark": landmark or (loc.landmark if loc else None),
        },
        "caller": {
            "name": session.patient.name or "unknown",
            "phone": session.patient.phone or "unknown",
            "relation": "self",
        },  # fmt: skip
        "patient": {"name": p.name or "unknown", "age": p.age, "sex": p.sex, "count": 1},
        "clinical": {
            "chief_complaint": note.chief_complaint,
            "consciousness_avpu": "U" if "unresponsive" in flags else "A",
            "breathing": "laboured" if "not_breathing" in flags else "unknown",
            "bleeding": "severe" if "severe_bleeding" in flags else "none reported",
            "symptoms": note.associated_symptoms,
            "onset": note.hpi.onset or "unknown",
            "history": note.history.conditions,
            "allergies": note.history.allergies,
            "medications": note.history.medications,
            "atmist": {
                "age": age_sex,
                "time_of_onset": note.hpi.onset or "unknown",
                "mechanism_or_complaint": note.chief_complaint,
                "injuries_or_illness": f"Suspected {top}",
                "signs": ", ".join(signs) or "unknown",
                "treatment_so_far": "None",
            },
            "triage_note_text": note.note_text,
        },
        "destination_preference": {
            "type": "NEAREST_CAPABLE",
            "required_capabilities": note.required_facilities,
        },
    }


async def dispatch(
    session: CareSession, notify: Notify, landmark: str | None = None, trigger: str = "countdown"
) -> None:
    """Creates the dispatch and follows it; on 503 or any failure the app is told to call 108."""
    em = session.emergency
    if em.get("dispatch_id") or em.get("cancelled"):
        return
    em["dispatching"] = True
    body = build_request(session, landmark)
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(
                f"{base_url()}/v1/dispatch/requests", json=body, headers={"X-Api-Key": api_key()}
            )
    except httpx.HTTPError as exc:
        return await _failed(session, notify, "UNREACHABLE", str(exc)[:120])
    if resp.status_code == 503:
        return await _failed(session, notify, "NO_UNITS_AVAILABLE", "No ambulance available.")
    if resp.status_code != 201:
        return await _failed(session, notify, f"HTTP_{resp.status_code}", resp.text[:160])
    created = resp.json()
    em["dispatch_id"] = created["dispatch_id"]
    session.emit("dispatch_created", dispatch_id=created["dispatch_id"], trigger=trigger, priority=body["priority"],
                 service_level=body["service_level"],
                 driver_url=f"{base_url()}/driver/{created['dispatch_id']}?key={api_key()}")  # fmt: skip
    await notify(f"Saathi emergency: an ambulance has been requested ({body['priority']}). "
                 f"{body['clinical']['atmist']['injuries_or_illness']}. Please call them now.")  # fmt: skip
    await track(session, created["dispatch_id"])


async def _failed(session: CareSession, notify: Notify, reason: str, detail: str) -> None:
    session.emergency["dispatching"] = False
    alerted = await notify(
        "Saathi emergency: an ambulance could not be requested automatically. Call them and dial 108 now."
    )
    # The app says "I have alerted your family" only when this is true.
    session.emit("dispatch_failed", reason=reason, message=detail, family_alerted=alerted)


async def track(session: CareSession, dispatch_id: str) -> None:
    """Polls the dispatch every second (the mock also offers a WebSocket) and emits status and location events."""
    last_status, last_at, last_polyline = None, None, None
    loop = asyncio.get_running_loop()
    deadline = loop.time() + TRACK_LIMIT_S
    async with httpx.AsyncClient(timeout=8) as client:
        while loop.time() < deadline:
            try:
                resp = await client.get(
                    f"{base_url()}/v1/dispatch/{dispatch_id}", headers={"X-Api-Key": api_key()}
                )
                d = resp.json() if resp.status_code == 200 else None
            except (httpx.HTTPError, ValueError):
                d = None
            if d:
                polyline = d.get("route_polyline")
                new_route = (
                    polyline is not None and polyline != last_polyline
                )  # the hospital leg brings a new one
                if d["status"] != last_status or new_route:
                    last_status = d["status"]
                    route = _decode(polyline) if new_route else None
                    last_polyline = polyline or last_polyline
                    session.emergency["dispatch_status"] = last_status
                    session.emit("dispatch_status", status=last_status, vehicle=d.get("vehicle"), crew=d.get("crew"),
                                 route=route, timeline=d.get("timeline"),
                                 destination_hospital=d.get("destination_hospital"))  # fmt: skip
                loc = d.get("location")
                if loc and loc.get("updated_at") != last_at:
                    last_at = loc.get("updated_at")
                    session.emergency["last_location"] = loc
                    session.emit("ambulance_location", lat=loc["lat"], lng=loc["lng"], heading=loc.get("heading"),
                                 eta_sec=d.get("eta_sec"), distance_m=d.get("distance_m"))  # fmt: skip
                if last_status in TERMINAL:
                    return
            await asyncio.sleep(1.0)


async def cancel(session: CareSession, notify: Notify | None = None, reason: str = "PATIENT_OK") -> bool:
    """ "I'm OK" after dispatch: cancels it with the mock (allowed until the patient is on board)."""
    em = session.emergency
    em["cancelled"] = True
    dispatch_id = em.get("dispatch_id")
    if not dispatch_id:
        session.emit("emergency_cancelled", dispatched=False)
        return True
    if notify is not None:  # the family was told an ambulance was requested; tell them it was called off
        await notify(
            "Saathi update: they said they are OK and the ambulance was cancelled. Please call them to check."
        )
    try:
        async with httpx.AsyncClient(timeout=8) as client:
            resp = await client.post(f"{base_url()}/v1/dispatch/{dispatch_id}/cancel", json={"reason": reason},
                                     headers={"X-Api-Key": api_key()})  # fmt: skip
        ok = resp.status_code == 200
    except httpx.HTTPError:
        ok = False
    session.emit("emergency_cancelled", dispatched=True, ok=ok)
    return ok


def _decode(encoded: str) -> list[list[float]]:
    points: list[list[float]] = []
    index = lat = lng = 0
    while index < len(encoded):
        for axis in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if axis == 0:
                lat += delta
            else:
                lng += delta
        points.append([lat / 1e5, lng / 1e5])
    return points
