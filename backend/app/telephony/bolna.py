"""Real clinic calls through a Bolna-hosted voice agent.

Bolna dials, talks (Sarvam STT/TTS) and transcribes; the backend only starts the call and polls it, so no webhook
or public URL is needed. The agent is created once with `scripts/bolna_agent.py`; its id goes in BOLNA_AGENT_ID.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import httpx

from app.llm.clinic_call import CallLine, confirmed_from, summary_for_user, waiting_minutes
from app.telephony.calls import CallSession

API = "https://api.bolna.ai"
TERMINAL = {"completed", "no-answer", "busy", "failed", "canceled", "stopped", "error", "balance-low"}
_NOT_CONNECTED = {
    "busy": "The clinic's line was busy. Please try again in a few minutes.",
    "no-answer": "Nobody at the clinic picked up. Please try again later.",
}


class BolnaError(RuntimeError):
    pass


@dataclass
class BolnaConfig:
    api_key: str
    agent_id: str

    @property
    def ready(self) -> bool:
        return bool(self.api_key and self.agent_id)

    @staticmethod
    def from_env() -> BolnaConfig:
        return BolnaConfig(
            api_key=os.environ.get("BOLNA_API_KEY", "").strip(),
            agent_id=os.environ.get("BOLNA_AGENT_ID", "").strip(),
        )


def _request(cfg: BolnaConfig, method: str, path: str, body: dict[str, object] | None = None) -> dict:
    resp = httpx.request(
        method, API + path, json=body, headers={"Authorization": f"Bearer {cfg.api_key}"}, timeout=15
    )
    if resp.status_code >= 300:
        try:
            message = resp.json().get("message", resp.text)
        except ValueError:
            message = resp.text
        raise BolnaError(f"Bolna {resp.status_code}: {message}")
    return resp.json()


def place_call(cfg: BolnaConfig, to: str, user_data: dict[str, str]) -> str:
    """Queues an outbound call from the agent. Returns Bolna's execution id."""
    body = {"agent_id": cfg.agent_id, "recipient_phone_number": to, "user_data": user_data}
    return str(_request(cfg, "POST", "/call", body)["execution_id"])


def get_execution(cfg: BolnaConfig, execution_id: str) -> dict:
    return _request(cfg, "GET", f"/executions/{execution_id}")


def stop_call(cfg: BolnaConfig, execution_id: str) -> None:
    """Bolna can only cancel a call that is still queued; a live call ends when the agent says goodbye."""
    _request(cfg, "POST", f"/call/{execution_id}/stop")


def parse_transcript(text: str) -> list[CallLine]:
    """Bolna's transcript is "assistant: …" / "user: …" lines; the user is the clinic."""
    lines = []
    for raw in (text or "").splitlines():
        speaker, _, said = raw.partition(":")
        role = {"assistant": "agent", "user": "clinic"}.get(speaker.strip().lower())
        if role and said.strip():
            lines.append(CallLine(role=role, text=said.strip()[:500]))
    return lines[-30:]


def apply_execution(session: CallSession, execution: dict) -> None:
    """Copies a Bolna execution into the session the app polls."""
    status = str(execution.get("status") or session.status)
    session.status = status
    session.transcript = parse_transcript(str(execution.get("transcript") or ""))
    clinic_said = " ".join(line.text for line in session.transcript if line.role == "clinic")
    session.confirmed = confirmed_from(clinic_said)
    session.waiting_minutes = waiting_minutes(clinic_said)
    if status not in TERMINAL:
        return
    session.done = True
    if status in _NOT_CONNECTED:
        session.summary = _NOT_CONNECTED[status]
    elif not clinic_said:
        session.summary = "Nobody at the clinic spoke on the call. Please try again later."
    else:
        session.summary = summary_for_user(session.request, session.confirmed, session.waiting_minutes)
