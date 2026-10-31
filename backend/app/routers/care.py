"""Symptom-to-care API (docs/CARE.md, workflow §7). The app polls GET /care/{id}/events for progress."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from app.care import dispatch, orchestrator, places, triage
from app.care.models import BookRequest, CareStart, IntakeNote, IntakeTurn, Location
from app.care.session import CARE, CareSession
from app.config import Settings
from app.db import Database
from app.deps import db_dep, llm_dep, require_device, settings_dep
from app.llm.base import LLMClient
from app.welfare.scheduler import send_to_family

router = APIRouter(prefix="/care")


class DispatchBody(BaseModel):
    trigger: str = Field(default="countdown", pattern="^(countdown|send_now)$")
    landmark: str | None = Field(default=None, max_length=200)


def _family_notifier(settings: Settings, db: Database, device_id: str) -> dispatch.Notify:
    """Telegram to the saved family contacts (logged on the family page); a no-op for unconfigured devices."""

    async def notify(text: str) -> None:
        device = db.get_device(device_id)
        if device is not None:
            await send_to_family(settings, db, device, "emergency", text)

    return notify


def _session(session_id: str, device: str) -> CareSession:
    session = CARE.get(session_id, device)
    if session is None:
        raise HTTPException(404, "No such care session.")
    return session


@router.post("/session")
def start(body: CareStart, device: str = Depends(require_device)) -> dict[str, object]:
    session = CARE.create(device)
    session.style = body.reply_style
    session.location = body.location
    session.patient = body.patient
    session.emit("session_started", has_location=body.location is not None)
    return {"session_id": session.id}


@router.post("/{session_id}/location")
def set_location(session_id: str, body: Location, device: str = Depends(require_device)) -> dict[str, object]:
    session = _session(session_id, device)
    session.location = body
    return {"ok": True}


@router.post("/{session_id}/intake")
async def intake(
    session_id: str,
    body: IntakeTurn,
    device: str = Depends(require_device),
    llm: LLMClient = Depends(llm_dep),
) -> dict[str, object]:
    """One spoken turn: the first description, or the answer to the pending follow-up."""
    session = _session(session_id, device)
    if session.note is not None:
        raise HTTPException(409, "Intake is already finished.")
    if session.pending_question:
        triage.record_answer(session, session.pending_question, body.text)
    else:
        triage.record_utterance(session, body.text)
    step = await triage.next_step(session, llm)
    if isinstance(step, IntakeNote):
        session.pending_question = None
        session.emit("intake_note", note_text=step.note_text, emergency=step.emergency.model_dump(),
                     route=session.route, safety_flags=session.safety_flags)  # fmt: skip
        return {
            "done": True,
            "route": session.route,
            "note": step.model_dump(),
            "safety_flags": session.safety_flags,
        }
    session.pending_question = step
    session.emit("intake_question", text=step)
    return {"done": False, "question": step, "safety_flags": session.safety_flags}


@router.get("/{session_id}/events")
def events(
    session_id: str, after: int = Query(default=0, ge=0), device: str = Depends(require_device)
) -> dict[str, object]:
    return {"events": _session(session_id, device).events_after(after)}


@router.post("/{session_id}/search")
async def search(
    session_id: str, device: str = Depends(require_device), llm: LLMClient = Depends(llm_dep)
) -> dict[str, object]:
    """Path B: find, rank and call hospitals in the background."""
    session = _session(session_id, device)
    if session.note is None:
        raise HTTPException(409, "Finish the intake first.")
    if session.location is None and not places.demo_mode():
        raise HTTPException(409, "Location is needed to find hospitals.")
    orchestrator.spawn(orchestrator.find_and_call(session, llm))
    return {"started": True}


@router.post("/{session_id}/book")
async def book(
    session_id: str,
    body: BookRequest,
    device: str = Depends(require_device),
    llm: LLMClient = Depends(llm_dep),
) -> dict[str, object]:
    session = _session(session_id, device)
    if session.hospital(body.hospital_id) is None:
        raise HTTPException(404, "Unknown hospital for this session.")
    patient = body.patient or session.patient
    orchestrator.spawn(orchestrator.book(session, llm, body.hospital_id, body.slot, patient))
    return {"started": True}


# ------------------------------------------------------------------------------------------------ path A


@router.post("/{session_id}/emergency/alert")
def emergency_alert(session_id: str, device: str = Depends(require_device)) -> dict[str, object]:
    """The phone started the siren and countdown (A1)."""
    session = _session(session_id, device)
    seconds = dispatch.countdown_sec()
    session.emergency["countdown_started"] = True
    session.emit("emergency_alert", countdown_sec=seconds)
    return {"countdown_sec": seconds}


@router.post("/{session_id}/emergency/dispatch")
async def emergency_dispatch(
    session_id: str,
    body: DispatchBody,
    device: str = Depends(require_device),
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
) -> dict[str, object]:
    """Countdown reached zero, or "Send ambulance now" (A2). Progress arrives as session events."""
    session = _session(session_id, device)
    if session.note is None:
        raise HTTPException(409, "Finish the intake first.")
    if session.location is None:
        raise HTTPException(409, "Location is needed to send an ambulance; call 108.")
    if session.emergency.get("dispatch_id") or session.emergency.get("dispatching"):
        return {"started": False, "dispatch_id": session.emergency.get("dispatch_id")}
    session.emergency.pop("cancelled", None)
    notify = _family_notifier(settings, db, device)
    orchestrator.spawn(dispatch.dispatch(session, notify, body.landmark, body.trigger))
    return {"started": True}


@router.post("/{session_id}/emergency/cancel")
async def emergency_cancel(session_id: str, device: str = Depends(require_device)) -> dict[str, object]:
    """ "I'm OK": stops a pending countdown, or cancels a dispatch with reason PATIENT_OK (A7)."""
    session = _session(session_id, device)
    return {"cancelled": await dispatch.cancel(session)}
