"""Real AI phone call to the clinic through a Bolna-hosted voice agent (SPEC §7.7 stretch, telephony option).

The app calls POST /calls/clinic/real/start; Bolna dials and runs the conversation, and the app polls
GET /calls/clinic/real/{id}, which reads the Bolna execution, for the live transcript and the outcome. No webhook or
public URL is needed. Outbound calls only go to CALL_ALLOWED_NUMBERS.
"""

from __future__ import annotations

import logging
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.deps import require_device
from app.llm import clinic_call
from app.llm.clinic_call import ClinicCallRequest
from app.schemas import Appointment
from app.telephony.bolna import BolnaConfig, BolnaError, apply_execution, get_execution, stop_call
from app.telephony.bolna import place_call as bolna_place_call
from app.telephony.calls import SESSIONS, allowed_numbers, normalize_number

log = logging.getLogger("saathi.telephony")
router = APIRouter()


class RealCallStart(BaseModel):
    patient_name: str = Field(default="the patient", max_length=60)
    appointment: Appointment
    reply_style: Literal["en", "hi", "hinglish"] = "en"
    to: str | None = Field(default=None, max_length=20)


@router.post("/calls/clinic/real/start", dependencies=[Depends(require_device)])
def start_real_call(body: RealCallStart) -> dict[str, object]:
    bolna = BolnaConfig.from_env()
    if not bolna.ready:
        raise HTTPException(503, "Real calls need BOLNA_API_KEY and BOLNA_AGENT_ID.")
    allowed = allowed_numbers()
    to = normalize_number(body.to or "") or (allowed[0] if allowed else "")
    if not to or to not in allowed:
        raise HTTPException(403, "That number is not in CALL_ALLOWED_NUMBERS.")
    request = ClinicCallRequest(
        patient_name=body.patient_name,
        appointment=body.appointment,
        reply_style=body.reply_style,
        transcript=[],
    )
    session = SESSIONS.create(request, to)
    appt = request.appointment
    user_data = {
        "patient_name": request.patient_name,
        "doctor": appt.doctor or "the doctor",
        "clinic": appt.clinic or "the clinic",
        "appointment_time": clinic_call.when(appt),
    }
    try:
        session.call_sid = bolna_place_call(bolna, to, user_data)
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
    session.summary = session.summary or "You ended the call before the appointment was confirmed."
    return session.snapshot()
