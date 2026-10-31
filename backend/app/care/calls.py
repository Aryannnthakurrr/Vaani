"""Outbound hospital calls through Bolna for Path B: inquiry (B3) and booking (B6).

Each mode is its own Bolna agent (BOLNA_INQUIRY_AGENT_ID, BOLNA_BOOKING_AGENT_ID; create them with
`scripts/bolna_agent.py --mode inquiry|booking`). The backend places the call, polls the execution, pushes new
transcript lines and status changes as session events, and returns the transcript for extraction.
Only numbers in CALL_ALLOWED_NUMBERS are ever dialled.
"""

from __future__ import annotations

import asyncio
import os
import time
from dataclasses import dataclass

from app.care.models import CallOutcome
from app.care.session import CallState, CareSession
from app.telephony.bolna import TERMINAL, BolnaConfig, BolnaError, get_execution, parse_transcript, place_call
from app.telephony.calls import allowed_numbers

POLL_S = 2.5
_OUTCOME = {
    "completed": "COMPLETED",
    "no-answer": "NO_ANSWER",
    "busy": "NO_ANSWER",
    "failed": "NO_ANSWER",
    "canceled": "NO_ANSWER",
    "stopped": "NO_ANSWER",
    "error": "NO_ANSWER",
    "balance-low": "NO_ANSWER",
}


class CallsUnavailable(RuntimeError):
    pass


def call_max_s() -> int:
    try:
        return max(30, int(os.environ.get("CALL_MAX_SEC", "150")))
    except ValueError:
        return 150


def agent_config(mode: str) -> BolnaConfig:
    agent = os.environ.get("BOLNA_INQUIRY_AGENT_ID" if mode == "inquiry" else "BOLNA_BOOKING_AGENT_ID", "")
    return BolnaConfig(api_key=os.environ.get("BOLNA_API_KEY", "").strip(), agent_id=agent.strip())


def calls_ready() -> bool:
    return agent_config("inquiry").ready and agent_config("booking").ready


def allowed(number: str | None) -> bool:
    return bool(number) and number in allowed_numbers()


@dataclass
class CallRun:
    execution_id: str | None
    outcome: CallOutcome
    transcript: list[dict[str, str]]


async def run_call(
    session: CareSession, hospital_id: str, number: str | None, mode: str, user_data: dict[str, str]
) -> CallRun:
    """Places one call and follows it to the end, emitting call_status / call_transcript events on the way."""
    if not allowed(number):
        session.emit("call_status", hospital_id=hospital_id, mode=mode, status="not_allowed")
        return CallRun(None, "NOT_ALLOWED", [])
    cfg = agent_config(mode)
    if not cfg.ready:
        raise CallsUnavailable(f"Calls need BOLNA_API_KEY and BOLNA_{mode.upper()}_AGENT_ID.")
    try:
        execution_id = await asyncio.to_thread(place_call, cfg, str(number), user_data)
    except (BolnaError, OSError) as exc:
        session.emit("call_status", hospital_id=hospital_id, mode=mode, status="failed", error=str(exc)[:200])
        return CallRun(None, "NO_ANSWER", [])
    state = CallState(hospital_id=hospital_id, mode=mode, execution_id=execution_id, status="queued")
    session.calls[execution_id] = state
    session.emit("call_status", call_sid=execution_id, hospital_id=hospital_id, mode=mode, status="queued")
    deadline = time.monotonic() + call_max_s() + 90  # ringing and queueing come on top of the talk limit
    while time.monotonic() < deadline:
        await asyncio.sleep(POLL_S)
        try:
            execution = await asyncio.to_thread(get_execution, cfg, execution_id)
        except (BolnaError, OSError):
            continue  # keep the last state; poll again
        _apply(session, state, execution)
        if state.done:
            break
    else:
        state.done = True
        session.emit(
            "call_status", call_sid=execution_id, hospital_id=hospital_id, mode=mode, status="timeout"
        )
        return CallRun(execution_id, "TIMEOUT", state.transcript)
    outcome: CallOutcome = _OUTCOME.get(state.status, "COMPLETED")  # type: ignore[assignment]
    if outcome == "COMPLETED" and not any(line["role"] == "clinic" for line in state.transcript):
        outcome = "NO_ANSWER"
    if outcome == "COMPLETED" and _voicemail(state.transcript):
        outcome = "VOICEMAIL"
    return CallRun(execution_id, outcome, state.transcript)


def _apply(session: CareSession, state: CallState, execution: dict) -> None:
    status = str(execution.get("status") or state.status)
    lines = [line.model_dump() for line in parse_transcript(str(execution.get("transcript") or ""))]
    for line in lines[len(state.transcript) :]:
        speaker = "hospital" if line["role"] == "clinic" else "agent"
        session.emit("call_transcript", call_sid=state.execution_id, hospital_id=state.hospital_id,
                     speaker=speaker, text=line["text"])  # fmt: skip
    state.transcript = lines
    if status != state.status:
        state.status = status
        session.emit("call_status", call_sid=state.execution_id, hospital_id=state.hospital_id,
                     mode=state.mode, status=status)  # fmt: skip
    state.done = status in TERMINAL


def _voicemail(transcript: list[dict[str, str]]) -> bool:
    said = " ".join(line["text"] for line in transcript if line["role"] == "clinic").lower()
    return any(cue in said for cue in ("leave a message", "after the beep", "voicemail", "not reachable"))
