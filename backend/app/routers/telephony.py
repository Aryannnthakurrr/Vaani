"""Real AI phone call to the clinic over Bolna or Twilio (SPEC §7.7 stretch, telephony option).

When BOLNA_API_KEY and BOLNA_AGENT_ID are set, Bolna's hosted agent makes the call and the status endpoint polls it
(see app/telephony/bolna.py). Otherwise the Twilio flow below runs.

Flow: the app calls POST /calls/clinic/real/start → Twilio dials the clinic → Twilio fetches TwiML from
/calls/twilio/voice and posts each spoken reply to /calls/twilio/gather. Every turn reuses the same
`clinic_call_turn` brain as the simulated call. The app polls GET /calls/clinic/real/{id} for the live transcript.

Safety: Twilio webhooks must carry a valid X-Twilio-Signature; outbound calls only go to CALL_ALLOWED_NUMBERS.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from app.config import Settings
from app.deps import llm_dep, require_device, settings_dep
from app.llm import clinic_call
from app.llm.base import LLMClient
from app.llm.clinic_call import CallLine, ClinicCallRequest, ClinicCallTurn, ClinicPlace
from app.memory import name_for
from app.schemas import Appointment
from app.telephony import twiml
from app.telephony.bolna import BolnaConfig, BolnaError, apply_execution, get_execution, stop_call
from app.telephony.bolna import place_call as bolna_place_call
from app.telephony.calls import (
    MAX_AGENT_TURNS,
    SESSIONS,
    CallSession,
    TwilioConfig,
    TwilioError,
    hang_up,
    normalize_number,
    place_call,
)

log = logging.getLogger("saathi.telephony")
router = APIRouter()
XML = "application/xml"


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


def _public_url(settings: Settings, path: str) -> str:
    return settings.public_base_url.rstrip("/") + path


@router.post("/calls/clinic/real/start")
async def start_real_call(
    body: RealCallStart, settings: Settings = Depends(settings_dep), device: str = Depends(require_device)
) -> dict[str, object]:
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
    cfg = TwilioConfig.from_env()
    bolna = BolnaConfig.from_env()
    if not bolna.ready:
        if not cfg.ready:
            raise HTTPException(
                503,
                "Real calls need BOLNA_API_KEY and BOLNA_AGENT_ID, "
                "or TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM_NUMBER.",
            )
        if not settings.public_base_url.startswith("https://"):
            raise HTTPException(
                503, "Real calls need PUBLIC_BASE_URL (https) so Twilio can reach this server."
            )
    to = normalize_number(body.to or "") or (sorted(cfg.allowed_numbers)[0] if cfg.allowed_numbers else "")
    if not to or to not in cfg.allowed_numbers:
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
    if bolna.ready:
        return await asyncio.to_thread(_start_bolna, session, bolna)  # the HTTP calls block
    try:
        session.call_sid = await asyncio.to_thread(
            place_call,
            cfg,
            to,
            _public_url(settings, f"/calls/twilio/voice?s={session.id}"),
            _public_url(settings, f"/calls/twilio/status?s={session.id}"),
        )
    except (TwilioError, OSError) as exc:
        session.status, session.error, session.done = "failed", str(exc), True
        log.warning("twilio call failed: %s", exc)
        raise HTTPException(502, str(exc)) from exc
    session.status = "dialing"
    log.info("clinic call placed session=%s", session.id)
    return session.snapshot()


def _start_bolna(session: CallSession, cfg: BolnaConfig) -> dict[str, object]:
    req = session.request
    user_data = {
        "patient_name": req.patient_name,
        "doctor": req.appointment.doctor or "the doctor",
        "clinic": clinic_call.clinic_name(req),
        "appointment_time": clinic_call.when(req.appointment),
        "task": clinic_call.task_brief(req),  # purpose, reason, needed facilities, time window
    }
    session.provider = "bolna"
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
    if session.provider == "bolna" and session.call_sid and not session.done:
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
            if session.provider == "bolna":
                stop_call(BolnaConfig.from_env(), session.call_sid)
            else:
                hang_up(TwilioConfig.from_env(), session.call_sid)
        except (TwilioError, BolnaError, OSError) as exc:
            log.warning("hang up failed: %s", exc)
    session.done = True
    session.status = "completed"
    session.summary = session.summary or clinic_call.call_note("user_ended", session.request.reply_style)
    return session.snapshot()


async def _verified(request: Request, settings: Settings) -> tuple[CallSession, dict[str, str]]:
    """Checks Twilio's signature against the public URL Twilio actually called, then loads the session."""
    form = {k: str(v) for k, v in (await request.form()).items()}
    query = request.url.query
    url = _public_url(settings, request.url.path + (f"?{query}" if query else ""))
    if not twiml.signature_valid(
        TwilioConfig.from_env().auth_token, url, form, request.headers.get("X-Twilio-Signature")
    ):
        raise HTTPException(403, "Bad Twilio signature.")
    session = SESSIONS.get(request.query_params.get("s", ""))
    if session is None:
        raise HTTPException(404, "No such call.")
    return session, form


def _hints(session: CallSession) -> str:
    appt = session.request.appointment
    return ", ".join(x for x in (appt.doctor, appt.clinic, "confirmed", "minutes", "haan", "nahi") if x)


async def _next_line(session: CallSession, llm: LLMClient, settings: Settings) -> str:
    """Asks the clinic-call brain for Saathi's next line and turns it into TwiML."""
    req = session.request.model_copy(update={"transcript": list(session.transcript)})
    turn: ClinicCallTurn = await llm.clinic_call_turn(req)
    if session.agent_turns() + 1 >= MAX_AGENT_TURNS and not turn.done:
        turn = ClinicCallTurn(
            say=turn.say + " Thank you, goodbye.",
            done=True,
            confirmed=turn.confirmed,
            waiting_minutes=turn.waiting_minutes,
        )
    session.transcript.append(CallLine(role="agent", text=turn.say))
    session.confirmed = turn.confirmed if turn.confirmed is not None else session.confirmed
    session.waiting_minutes = (
        turn.waiting_minutes if turn.waiting_minutes is not None else session.waiting_minutes
    )
    session.has_facilities = (
        turn.has_facilities if turn.has_facilities is not None else session.has_facilities
    )
    session.slot = turn.slot or session.slot
    if turn.done:
        session.done = True
        session.summary = turn.summary or session.closing_summary()
        return twiml.say_and_hang_up(turn.say)
    return twiml.say_and_listen(
        turn.say, _public_url(settings, f"/calls/twilio/gather?s={session.id}"), hints=_hints(session)
    )


@router.post("/calls/twilio/voice")
async def twilio_voice(
    request: Request, settings: Settings = Depends(settings_dep), llm: LLMClient = Depends(llm_dep)
) -> Response:
    session, _ = await _verified(request, settings)
    session.status = "in-progress"
    return Response(await _next_line(session, llm, settings), media_type=XML)


@router.post("/calls/twilio/gather")
async def twilio_gather(
    request: Request, settings: Settings = Depends(settings_dep), llm: LLMClient = Depends(llm_dep)
) -> Response:
    session, form = await _verified(request, settings)
    heard = form.get("SpeechResult", "").strip()
    if not heard:
        session.empty_replies += 1
        if session.empty_replies >= 2:
            session.done = True
            heard_enough = session.confirmed or session.slot or session.has_facilities is False
            style = session.request.reply_style
            session.summary = (
                session.closing_summary() if heard_enough else clinic_call.call_note("not_heard", style)
            )
            return Response(
                twiml.say_and_hang_up("Sorry, I cannot hear you. I will call again later. Goodbye."),
                media_type=XML,
            )
        last = next((line.text for line in reversed(session.transcript) if line.role == "agent"), "")
        return Response(
            twiml.say_and_listen(
                "Sorry, I could not hear you. " + last,
                _public_url(settings, f"/calls/twilio/gather?s={session.id}"),
                hints=_hints(session),
            ),
            media_type=XML,
        )
    session.empty_replies = 0
    session.transcript.append(CallLine(role="clinic", text=heard[:500]))
    return Response(await _next_line(session, llm, settings), media_type=XML)


@router.post("/calls/twilio/status")
async def twilio_status(request: Request, settings: Settings = Depends(settings_dep)) -> Response:
    session, form = await _verified(request, settings)
    status = form.get("CallStatus", "")
    if status:
        session.status = status
    if status in {"busy", "no-answer", "failed", "canceled"} and not session.done:
        session.done = True
        session.summary = clinic_call.call_note(
            status if status in {"busy", "no-answer"} else "not_connected", session.request.reply_style
        )
    if status == "completed" and not session.done:
        session.done = True
        session.summary = session.summary or clinic_call.call_note("ended_early", session.request.reply_style)
    log.info("clinic call session=%s status=%s", session.id, status)
    return Response(status_code=204)
