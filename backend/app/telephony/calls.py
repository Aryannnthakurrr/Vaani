"""Call sessions and the Twilio REST client for real clinic calls.

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

import httpx

from app.llm.clinic_call import CallLine, ClinicCallRequest

MAX_AGENT_TURNS = 6
SESSION_TTL_S = 3600


@dataclass
class TwilioConfig:
    account_sid: str
    auth_token: str
    from_number: str
    allowed_numbers: frozenset[str]

    @property
    def ready(self) -> bool:
        return bool(self.account_sid and self.auth_token and self.from_number)

    @staticmethod
    def from_env() -> TwilioConfig:
        allowed = {
            normalize_number(n) for n in os.environ.get("CALL_ALLOWED_NUMBERS", "").split(",") if n.strip()
        }
        return TwilioConfig(
            account_sid=os.environ.get("TWILIO_ACCOUNT_SID", "").strip(),
            auth_token=os.environ.get("TWILIO_AUTH_TOKEN", "").strip(),
            from_number=normalize_number(os.environ.get("TWILIO_FROM_NUMBER", "")),
            allowed_numbers=frozenset(n for n in allowed if n),
        )


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


@dataclass
class CallSession:
    id: str
    request: ClinicCallRequest
    to: str
    status: str = "starting"
    call_sid: str | None = None
    transcript: list[CallLine] = field(default_factory=list)
    empty_replies: int = 0
    done: bool = False
    confirmed: bool | None = None
    waiting_minutes: int | None = None
    summary: str | None = None
    error: str | None = None
    created: float = field(default_factory=time.time)

    def agent_turns(self) -> int:
        return sum(1 for line in self.transcript if line.role == "agent")

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


class TwilioError(RuntimeError):
    pass


def place_call(cfg: TwilioConfig, to: str, voice_url: str, status_url: str) -> str:
    """Start an outbound call that fetches its TwiML from `voice_url`. Returns the Call SID."""
    resp = httpx.post(
        f"https://api.twilio.com/2010-04-01/Accounts/{cfg.account_sid}/Calls.json",
        auth=(cfg.account_sid, cfg.auth_token),
        data={
            "To": to,
            "From": cfg.from_number,
            "Url": voice_url,
            "Method": "POST",
            "StatusCallback": status_url,
            "StatusCallbackMethod": "POST",
            "StatusCallbackEvent": ["initiated", "ringing", "answered", "completed"],
            "Timeout": "30",
        },
        timeout=15,
    )
    if resp.status_code >= 300:
        try:
            message = resp.json().get("message", resp.text)
        except ValueError:
            message = resp.text
        raise TwilioError(f"Twilio {resp.status_code}: {message}")
    return str(resp.json()["sid"])


def hang_up(cfg: TwilioConfig, call_sid: str) -> None:
    """Ends a live call (the user pressed "End call" in the app)."""
    resp = httpx.post(
        f"https://api.twilio.com/2010-04-01/Accounts/{cfg.account_sid}/Calls/{call_sid}.json",
        auth=(cfg.account_sid, cfg.auth_token),
        data={"Status": "completed"},
        timeout=15,
    )
    if resp.status_code >= 300:
        raise TwilioError(f"Twilio {resp.status_code}: could not end the call")
