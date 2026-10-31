"""Confirming an appointment while the hospital is still on the line (workflow B3–B6, two-way).

The Bolna inquiry agent has a custom function tool, `check_with_patient` (scripts/bolna_agent.py). When the
hospital offers a concrete slot, the agent says "one moment" and calls `POST /care/tools/check_with_patient`; we push
a `live_confirm` event to the phone, which asks the user (voice or tap) and answers through
`POST /care/{id}/live_confirm`. Yes → the agent books in the same call with the patient's details; no or silence →
it says it will call back, and the usual recommendation and booking call follow.
"""

from __future__ import annotations

import asyncio
import os
import secrets

from app.care.extract import parse_slots
from app.care.models import Slot
from app.care.session import CareSession

WAIT_S = 25.0  # how long the hospital is kept waiting; the agent says "one moment" meanwhile


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
    """Puts the offer to the user and waits for their answer. Returns what the agent should do next."""
    if session.live_booked:
        return {
            "decision": "no",
            "say": "The patient has already booked elsewhere. Thank them and end the call.",
        }
    hospital = session.hospital(hospital_id)
    if hospital is None:
        return {"decision": "no_answer", "say": "Say you will confirm with the patient and call back."}
    ask_id = secrets.token_urlsafe(6)
    future: asyncio.Future[bool] = asyncio.get_running_loop().create_future()
    session.live_asks[ask_id] = future
    session.emit("live_confirm", ask_id=ask_id, hospital_id=hospital_id, hospital_name=hospital.name,
                 doctor=doctor[:80], slot=slot_text[:80], fee=fee[:20], wait_s=WAIT_S)  # fmt: skip
    try:
        yes = await asyncio.wait_for(future, WAIT_S)
    except TimeoutError:
        session.emit("live_confirm_done", ask_id=ask_id, decision="no_answer")
        return {
            "decision": "no_answer",
            "say": "The patient didn't answer in time. Say you'll call back shortly.",
        }
    finally:
        session.live_asks.pop(ask_id, None)
    if not yes:
        session.emit("live_confirm_done", ask_id=ask_id, decision="no")
        return {"decision": "no", "say": "The patient said no for now. Thank them and say you'll call back."}
    slots = parse_slots(slot_text)
    session.live_booked[hospital_id] = slots[0] if slots else Slot(start=slot_text)
    session.emit("live_confirm_done", ask_id=ask_id, decision="yes")
    p = session.patient
    return {
        "decision": "yes",
        "say": "Book this slot now, then ask for the booking or token number and any instructions.",
        "patient_name": p.name or "the patient",
        "patient_age": str(p.age or ""),
        "patient_phone": p.phone or "",
    }


def answer(session: CareSession, ask_id: str, yes: bool) -> bool:
    """The phone's answer. False when the question already timed out or was answered."""
    future = session.live_asks.get(ask_id)
    if future is None or future.done():
        return False
    future.set_result(yes)
    return True
