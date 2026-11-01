"""What the doctor feature remembers after a booking, so later requests can use it (docs/MEKO_STATE.md).

A confirmed booking becomes one keyed fact, `doctor/next_appointment`, written as the `saathi-care` agent with where
it was learnt ("booking call to Fortis Memorial Gurugram"). The text is what the voice assistant reads ("Appointment
with Dr. Gupta at Fortis…, Sat 3 Nov 11:00"); the metadata holds the structured parts the phone needs to act on it
(address and coordinates for the cab, the start time for reminders). Like every fact it lands in the local store at
once and in the user's Meko datapack in the background, so it outlives the session, an app reinstall or a new phone.
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from app.care.models import BookingResult, Hospital, Slot
from app.care.session import CareSession
from app.memory import get_memory

log = logging.getLogger("saathi.care.remembered")
KIND, KEY, AGENT = "doctor", "next_appointment", "saathi-care"


def owner(session: CareSession) -> str:
    """Whose memory: the demo persona when one is active, else the phone."""
    return f"persona:{session.profile}" if session.profile else session.device


def _when(start_iso: str) -> str:
    try:
        return datetime.fromisoformat(start_iso).strftime("%a %d %b, %H:%M")
    except ValueError:
        return start_iso


async def remember_booking(
    session: CareSession, hospital: Hospital, booking: BookingResult, slot: Slot, how: str
) -> None:
    """`how`: "booking call" or "while the hospital was on the line"."""
    inquiry = session.results.get(hospital.id)
    doctor = booking.doctor_name or (inquiry.doctor_name if inquiry else None) or "the doctor"
    specialty = session.note.specialty if session.note else ""
    start = booking.start_iso or slot.start
    text = f"Appointment with {doctor}" + (f" ({specialty})" if specialty else "") + f" at {hospital.name}"
    text += (f", {hospital.address}" if hospital.address else "") + f", {_when(start)}"
    meta: dict[str, Any] = {
        "agent": AGENT,
        "learned_from": f"{how} to {hospital.name}" if how == "booking call" else f"{how} ({hospital.name})",
        "clinic": hospital.name, "address": hospital.address, "lat": hospital.lat, "lng": hospital.lng,
        "doctor": doctor, "specialty": specialty, "start_iso": start, "booking_ref": booking.booking_ref or "",
        "phone": hospital.phone or "",
    }  # fmt: skip
    try:
        await get_memory().remember(owner(session), text, KIND, KEY, **meta)
    except Exception as exc:  # noqa: BLE001 - memory never breaks a booking
        log.warning("appointment not remembered: %s", type(exc).__name__)


async def next_appointment(user: str) -> dict[str, Any] | None:
    """The remembered appointment if it is still ahead, with who learnt it and where."""
    memory = get_memory()
    entries = await memory.entries(user) if hasattr(memory, "entries") else []
    for e in entries:
        if e["topic"] == KIND and e["key"] == KEY:
            meta = e["meta"]
            try:
                if datetime.fromisoformat(str(meta.get("start_iso"))) < datetime.now().astimezone():
                    return None
            except (ValueError, TypeError):  # unreadable, or no offset
                return None
            return {"text": e["text"], **{k: meta.get(k) for k in (
                "clinic", "address", "lat", "lng", "doctor", "specialty", "start_iso", "booking_ref", "phone",
                "agent", "learned_from")}}  # fmt: skip
    return None
