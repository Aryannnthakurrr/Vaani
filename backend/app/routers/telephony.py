"""Real AI phone call to the clinic through a Bolna-hosted voice agent (SPEC §7.7 stretch, telephony option).

The app calls POST /calls/clinic/real/start; Bolna dials and runs the conversation, and the app polls
GET /calls/clinic/real/{id}, which reads the Bolna execution, for the live transcript and the outcome. No webhook or
public URL is needed. Outbound calls only go to CALL_ALLOWED_NUMBERS.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.deps import require_device
from app.llm import clinic_call
from app.llm.clinic_call import ClinicCallRequest, ClinicPlace
from app.memory import name_for
from app.schemas import Appointment
from app.telephony.bolna import BolnaConfig, BolnaError, apply_execution, get_execution, stop_call
from app.telephony.bolna import place_call as bolna_place_call
from app.telephony.calls import SESSIONS, CallSession, allowed_numbers, normalize_number

log = logging.getLogger("saathi.telephony")
router = APIRouter()


class RealCallStart(BaseModel):
    patient_name: str | None = Field(default=None, max_length=60)  # empty: the elder's stored name is used
    appointment: Appointment = Field(default_factory=Appointment)
    reply_style: Literal["en", "hi", "hinglish"] = "en"
    to: str | None = Field(default=None, max_length=20)
    purpose: Literal["confirm", "book"] = "confirm"
    reason: str | None = Field(default=None, max_length=200)
    needs: list[Annotated[str, Field(max_length=40)]] = Field(default_factory=list, max_length=8)
    window: str | None = Field(default=None, max_length=80)
    place: ClinicPlace | None = None
    # A booking is irreversible, so the app sends this only after the elder said or tapped yes to the summary.
    user_confirmed: bool = False


@router.post("/calls/clinic/real/start")
async def start_real_call(body: RealCallStart, device: str = Depends(require_device)) -> dict[str, object]:
    patient = await name_for(device, body.patient_name)
    if body.purpose == "book":
        if not body.user_confirmed:
            raise HTTPException(409, "A booking call needs the user's yes first.")
        if not patient:
            raise HTTPException(
                422, "The clinic needs the patient's name to book. Ask the user for it first."
            )
        if not body.reason:
            raise HTTPException(422, "A booking call needs the reason for the visit.")
    bolna = BolnaConfig.from_env()
    if not bolna.ready:
        raise HTTPException(503, "Real calls need BOLNA_API_KEY and BOLNA_AGENT_ID.")
    allowed = allowed_numbers()
    to = normalize_number(body.to or "") or (allowed[0] if allowed else "")
    if not to or to not in allowed:
        raise HTTPException(403, "That number is not in CALL_ALLOWED_NUMBERS.")
    request = ClinicCallRequest(
        patient_name=patient or "the patient",
        appointment=body.appointment,
        reply_style=body.reply_style,
        transcript=[],
        purpose=body.purpose,
        reason=body.reason,
        needs=body.needs,
        window=body.window,
        place=body.place,
    )
    session = SESSIONS.create(request, to)
    return await asyncio.to_thread(_start_bolna, session, bolna)  # the HTTP calls block


def _start_bolna(session: CallSession, cfg: BolnaConfig) -> dict[str, object]:
    req = session.request
    user_data = {
        "patient_name": req.patient_name,
        "doctor": req.appointment.doctor or "the doctor",
        "clinic": clinic_call.clinic_name(req),
        "appointment_time": clinic_call.when(req.appointment),
        "task": clinic_call.task_brief(req),  # purpose, reason, needed facilities, time window
    }
    try:
        session.call_sid = bolna_place_call(cfg, session.to, user_data)
    except (BolnaError, OSError) as exc:
        session.status, session.error, session.done = "failed", str(exc), True
        log.warning("bolna call failed: %s", exc)
        raise HTTPException(502, str(exc)) from exc
    session.status = "queued"
    log.info("clinic call placed via bolna session=%s", session.id)
    return session.snapshot()


@router.get("/calls/clinic/real/{session_id}", dependencies=[Depends(require_device)])
def real_call_status(session_id: str) -> dict[str, object]:
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(404, "No such call.")
    if session.call_sid and not session.done:
        try:
            apply_execution(session, get_execution(BolnaConfig.from_env(), session.call_sid))
        except (BolnaError, OSError) as exc:
            log.warning("bolna status failed: %s", exc)  # keep the last state; the app polls again
    return session.snapshot()


@router.post("/calls/clinic/real/{session_id}/hangup", dependencies=[Depends(require_device)])
def real_call_hangup(session_id: str) -> dict[str, object]:
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(404, "No such call.")
    if session.call_sid and not session.done:
        try:
            stop_call(BolnaConfig.from_env(), session.call_sid)
        except (BolnaError, OSError) as exc:
            log.warning("hang up failed: %s", exc)
    session.done = True
    session.status = "completed"
    session.summary = session.summary or clinic_call.call_note("user_ended", session.request.reply_style)
    return session.snapshot()
