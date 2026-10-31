"""Symptom-to-care API (docs/CARE.md, workflow §7). The app polls GET /care/{id}/events for progress."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, Field

from app.care import dispatch, live_confirm, orchestrator, places, triage
from app.care.models import BookRequest, CareStart, IntakeTurn, Location, Patient
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

    async def notify(text: str) -> bool:
        device = db.get_device(device_id)
        return device is not None and bool(await send_to_family(settings, db, device, "emergency", text))

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
    return await triage.take_turn(session, body.text, llm)


@router.get("/{session_id}/events")
async def events(
    session_id: str,
    after: int = Query(default=0, ge=0),
    wait: float = Query(default=0, ge=0, le=25),
    device: str = Depends(require_device),
) -> dict[str, object]:
    """`wait` > 0 holds the request open until something happens (long-poll); 0 answers at once."""
    return {"events": await _session(session_id, device).wait_events(after, wait)}


class SearchBody(BaseModel):
    patient: Patient | None = None


@router.post("/{session_id}/search")
async def search(
    session_id: str,
    body: SearchBody | None = None,
    device: str = Depends(require_device),
    llm: LLMClient = Depends(llm_dep),
) -> dict[str, object]:
    """Path B: find, rank and call hospitals in the background."""
    session = _session(session_id, device)
    if body is not None and body.patient is not None:
        session.patient = body.patient  # so a yes during an inquiry call can book at once
    if session.note is None:
        raise HTTPException(409, "Finish the intake first.")
    if session.location is None and not places.demo_mode():
        raise HTTPException(409, "Location is needed to find hospitals.")
    orchestrator.spawn(orchestrator.find_and_call(session, llm), session.id)
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
    orchestrator.spawn(orchestrator.book(session, llm, body.hospital_id, body.slot, patient), session.id)
    return {"started": True}


@router.post("/{session_id}/cancel")
async def cancel(session_id: str, device: str = Depends(require_device)) -> dict[str, object]:
    """The user left the doctor screen or started something else: stop its hospital and booking calls."""
    return {"stopped": await orchestrator.cancel_session(_session(session_id, device))}


class CheckWithPatient(BaseModel):
    """What the Bolna inquiry agent sends mid-call (scripts/bolna_agent.py, tool check_with_patient)."""

    session_ref: str = Field(max_length=40)
    hospital_ref: str = Field(max_length=80)
    doctor: str = Field(default="", max_length=200)
    slot: str = Field(default="", max_length=200)
    fee: str = Field(default="", max_length=40)


class LiveAnswer(BaseModel):
    ask_id: str = Field(max_length=20)
    yes: bool


@router.post("/tools/check_with_patient")
async def check_with_patient(
    body: CheckWithPatient, authorization: str | None = Header(default=None)
) -> dict[str, object]:
    """Called by the hospital-call agent while the hospital waits; answers once the user says yes or no."""
    if not live_confirm.authorised(authorization):
        raise HTTPException(401, "Unknown caller.")
    session = CARE.get_any(body.session_ref)
    if session is None:
        return {"decision": "no_answer", "say": "Say you will confirm with the patient and call back."}
    return await live_confirm.ask(session, body.hospital_ref, body.doctor, body.slot, body.fee)


@router.post("/{session_id}/live_confirm")
def live_answer(
    session_id: str, body: LiveAnswer, device: str = Depends(require_device)
) -> dict[str, object]:
    """The user's yes or no to a slot offered on a call that is still going on."""
    return {"accepted": live_confirm.answer(_session(session_id, device), body.ask_id, body.yes)}


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
    if session.emergency.get("dispatch_id") or session.emergency.get("dispatching"):
        return {"started": False, "dispatch_id": session.emergency.get("dispatch_id")}
    notify = _family_notifier(settings, db, device)
    if (
        session.location is None
    ):  # no address to send: the app shows "call 108" from the dispatch_failed event
        await dispatch._failed(session, notify, "NO_LOCATION", "Location is needed to send an ambulance.")
        return {"started": False, "reason": "NO_LOCATION"}
    session.emergency.pop("cancelled", None)
    orchestrator.spawn(dispatch.dispatch(session, notify, body.landmark, body.trigger))
    return {"started": True}


@router.post("/{session_id}/emergency/cancel")
async def emergency_cancel(
    session_id: str,
    device: str = Depends(require_device),
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
) -> dict[str, object]:
    """ "I'm OK": stops a pending countdown, or cancels a dispatch with reason PATIENT_OK (A7)."""
    session = _session(session_id, device)
    return {"cancelled": await dispatch.cancel(session, _family_notifier(settings, db, device))}
