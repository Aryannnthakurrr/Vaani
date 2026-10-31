"""In-memory care sessions with an ordered event log the app polls (workflow §7–8).

A session belongs to the device that created it. Events carry symptoms and call transcripts, so only event types are
logged, never their contents (DEVELOPING.md: raw personal text is never logged).
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any

from app.care.models import (
    BookingResult,
    CallResult,
    Hospital,
    IntakeNote,
    Location,
    Patient,
    Recommendation,
    Slot,
)

log = logging.getLogger("saathi.care")
SESSION_TTL_S = 6 * 3600
MAX_FOLLOWUPS = 3


@dataclass
class CallState:
    """One outbound call (inquiry or booking) and what it produced."""

    hospital_id: str
    mode: str  # inquiry | booking
    execution_id: str | None = None
    status: str = "starting"
    transcript: list[dict[str, str]] = field(default_factory=list)
    done: bool = False


@dataclass
class CareSession:
    id: str
    device: str
    style: str = "en"
    location: Location | None = None
    patient: Patient = field(default_factory=Patient)
    created: float = field(default_factory=time.time)
    # intake
    utterances: list[str] = field(default_factory=list)
    followups: list[dict[str, str]] = field(default_factory=list)  # {"q": ..., "a": ...}
    pending_question: str | None = None
    note: IntakeNote | None = None
    safety_flags: list[str] = field(default_factory=list)
    route: str | None = None
    # path B
    hospitals: list[Hospital] = field(default_factory=list)
    backups: list[str] = field(default_factory=list)
    calls: dict[str, CallState] = field(default_factory=dict)
    results: dict[str, CallResult] = field(default_factory=dict)
    recommendation: Recommendation | None = None
    chosen: tuple[str, Slot] | None = None
    booking: BookingResult | None = None
    booking_attempts: int = 0
    # confirming while the hospital is on the line (care/live_confirm.py)
    live_asks: dict[str, Any] = field(default_factory=dict)  # ask_id -> asyncio.Future[bool]
    live_booked: dict[str, Slot] = field(default_factory=dict)  # hospital_id -> slot the user said yes to
    # path A
    emergency: dict[str, Any] = field(default_factory=dict)
    # events
    events: list[dict[str, Any]] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def emit(self, kind: str, **data: Any) -> None:
        with self._lock:
            self.events.append({"seq": len(self.events) + 1, "type": kind, "at": time.time(), "data": data})
        log.info("care session=%s event=%s", self.id, kind)

    def events_after(self, seq: int) -> list[dict[str, Any]]:
        with self._lock:
            return [e for e in self.events if e["seq"] > seq]

    async def wait_events(self, seq: int, wait_s: float) -> list[dict[str, Any]]:
        """Long-poll: returns as soon as an event after `seq` exists, or empty after `wait_s`. The phone asks again at
        once, so updates arrive within ~0.1 s instead of a polling interval, with one request per update."""
        deadline = time.monotonic() + wait_s
        while True:
            events = self.events_after(seq)
            if events or time.monotonic() >= deadline:
                return events
            await asyncio.sleep(0.1)

    def hospital(self, hospital_id: str) -> Hospital | None:
        return next((h for h in self.hospitals if h.id == hospital_id), None)


class CareStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._sessions: dict[str, CareSession] = {}

    def create(self, device: str) -> CareSession:
        with self._lock:
            now = time.time()
            for sid in [k for k, s in self._sessions.items() if now - s.created > SESSION_TTL_S]:
                del self._sessions[sid]
            session = CareSession(id="s_" + secrets.token_urlsafe(9), device=device)
            self._sessions[session.id] = session
            return session

    def get(self, session_id: str, device: str) -> CareSession | None:
        with self._lock:
            session = self._sessions.get(session_id)
        return session if session and session.device == device else None

    def get_any(self, session_id: str) -> CareSession | None:
        """For server-side callers that carry no device header (the driver view, webhooks)."""
        with self._lock:
            return self._sessions.get(session_id)


CARE = CareStore()
