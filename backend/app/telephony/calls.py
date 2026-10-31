"""Call sessions and the outbound-number allowlist for real calls (made through Bolna, app/telephony/bolna.py).

A session holds the appointment, the transcript so far and the outcome. Sessions live in memory: a call lasts a
few minutes and the backend runs as a single container, so persistence would add nothing for the demo.
"""

from __future__ import annotations

import os
import re
import secrets
import threading
import time
from dataclasses import dataclass, field

from app.llm.clinic_call import CallLine, ClinicCallRequest

SESSION_TTL_S = 3600


def normalize_number(raw: str) -> str:
    """E.164 for Indian numbers written the usual ways: 7065220661, 07065220661, +91 70652 20661."""
    digits = re.sub(r"[^\d+]", "", raw or "")
    if not digits:
        return ""
    if digits.startswith("+"):
        return digits
    digits = digits.lstrip("0")
    if len(digits) == 10:
        return "+91" + digits
    return "+" + digits


def allowed_numbers() -> list[str]:
    """CALL_ALLOWED_NUMBERS in the order written: Saathi never dials anything else."""
    return [
        n for n in (normalize_number(x) for x in os.environ.get("CALL_ALLOWED_NUMBERS", "").split(",")) if n
    ]


@dataclass
class CallSession:
    id: str
    request: ClinicCallRequest
    to: str
    status: str = "starting"
    provider: str = "bolna"
    call_sid: str | None = None  # Bolna execution id
    transcript: list[CallLine] = field(default_factory=list)
    done: bool = False
    confirmed: bool | None = None
    waiting_minutes: int | None = None
    summary: str | None = None
    error: str | None = None
    created: float = field(default_factory=time.time)

    def snapshot(self) -> dict[str, object]:
        return {
            "id": self.id,
            "status": self.status,
            "done": self.done,
            "transcript": [line.model_dump() for line in self.transcript],
            "confirmed": self.confirmed,
            "waiting_minutes": self.waiting_minutes,
            "summary": self.summary,
            "error": self.error,
        }


class SessionStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._sessions: dict[str, CallSession] = {}

    def create(self, request: ClinicCallRequest, to: str) -> CallSession:
        with self._lock:
            now = time.time()
            for sid in [k for k, s in self._sessions.items() if now - s.created > SESSION_TTL_S]:
                del self._sessions[sid]
            session = CallSession(id=secrets.token_urlsafe(12), request=request, to=to)
            self._sessions[session.id] = session
            return session

    def get(self, session_id: str) -> CallSession | None:
        with self._lock:
            return self._sessions.get(session_id)


SESSIONS = SessionStore()
