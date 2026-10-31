"""Confirming an appointment while the hospital is still on the line (workflow B3–B6, two-way).

The Bolna inquiry agent has a custom function tool, `check_with_patient` (scripts/bolna_agent.py). When the
hospital offers a concrete slot, the agent says "one moment" and calls `POST /care/tools/check_with_patient`; we push
a `live_confirm` event to the phone, which asks the user (voice or tap) and answers through
`POST /care/{id}/live_confirm`. Yes → the agent books in the same call with the patient's details; no or silence →
it says it will call back, and the usual recommendation and booking call follow.

Bolna gives up on a tool request after a few seconds, so one request never waits long: it returns `waiting` after
HOLD_S, the agent tells the receptionist the patient is answering and calls again, and later calls for the same
hospital join the question already on the phone. The whole question stays open for WAIT_S.
"""

from __future__ import annotations

import asyncio
import os
import secrets
import time

from app.care.extract import parse_slots
from app.care.models import Slot
from app.care.session import CareSession

WAIT_S = 40.0  # how long the hospital is kept on the line in all
HOLD_S = 6.0  # longest single tool request; Bolna's own limit is shorter than WAIT_S


def tool_secret() -> str:
    return os.environ.get("BOLNA_TOOL_SECRET", "").strip()


def authorised(header: str | None) -> bool:
    """The tool's bearer token must match BOLNA_TOOL_SECRET; with no secret set the tool is off."""
    secret = tool_secret()
    token = (header or "").strip()
    while token.lower().startswith("bearer "):  # Bolna may add its own "Bearer " to the configured token
        token = token[7:].strip()
    return bool(secret) and secrets.compare_digest(token, secret)


async def ask(
    session: CareSession, hospital_id: str, doctor: str, slot_text: str, fee: str
) -> dict[str, object]:
    """Puts the offer to the user (or rejoins the open question) and waits up to HOLD_S for their answer."""
    if session.live_booked:
        return {
            "decision": "no",
            "say": "The patient has already booked elsewhere. Thank them and end the call.",
        }
    hospital = session.hospital(hospital_id)
    if hospital is None:
        return {"decision": "no_answer", "say": "Say you will confirm with the patient and call back."}
    ask_id, deadline, slot_text = session.live_pending.get(hospital_id) or _open(
        session, hospital_id, hospital.name, doctor, slot_text, fee
    )
    future = session.live_asks[ask_id]
    remaining = deadline - time.monotonic()
    try:
        yes = await asyncio.wait_for(asyncio.shield(future), max(0.0, min(HOLD_S, remaining)))
    except TimeoutError:
        if remaining > HOLD_S:
            return {
                "decision": "waiting",
                "say": "Say the patient is answering on their phone, ask them to hold a moment, then call "
                "check_with_patient again with the same details.",
            }
        _close(session, hospital_id, ask_id, "no_answer")
        return {
            "decision": "no_answer",
            "say": "The patient didn't answer in time. Say you'll call back shortly.",
        }
    _close(session, hospital_id, ask_id, "yes" if yes else "no")
    if not yes:
        return {"decision": "no", "say": "The patient said no for now. Thank them and say you'll call back."}
    slots = parse_slots(slot_text)
    session.live_booked[hospital_id] = slots[0] if slots else Slot(start=slot_text)
    p = session.patient
    return {
        "decision": "yes",
        "say": "Book this slot now, then ask for the booking or token number and any instructions.",
        "patient_name": p.name or "the patient",
        "patient_age": str(p.age or ""),
        "patient_phone": p.phone or "",
    }


def _open(
    session: CareSession, hospital_id: str, name: str, doctor: str, slot_text: str, fee: str
) -> tuple[str, float, str]:
    ask_id = secrets.token_urlsafe(6)
    session.live_asks[ask_id] = asyncio.get_running_loop().create_future()
    pending = (ask_id, time.monotonic() + WAIT_S, slot_text)
    session.live_pending[hospital_id] = pending
    session.emit("live_confirm", ask_id=ask_id, hospital_id=hospital_id, hospital_name=name,
                 doctor=doctor[:80], slot=slot_text[:80], fee=fee[:20], wait_s=WAIT_S)  # fmt: skip
    return pending


def _close(session: CareSession, hospital_id: str, ask_id: str, decision: str) -> None:
    session.live_asks.pop(ask_id, None)
    session.live_pending.pop(hospital_id, None)
    session.emit("live_confirm_done", ask_id=ask_id, decision=decision)


def answer(session: CareSession, ask_id: str, yes: bool) -> bool:
    """The phone's answer. False when the question already timed out or was answered."""
    future = session.live_asks.get(ask_id)
    if future is None or future.done():
        return False
    future.set_result(yes)
    return True
