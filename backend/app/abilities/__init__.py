"""Personal compiled abilities: work the LLM used to do for one user, now done by tested code (docs/PERSONAL_ABILITIES.md).

The app-wide skills in `app/skills` learn tasks everyone does (booking a cab). An ability is personal: it was
compiled from one user's own repeated requests (Ramesh's sugar readings, Sunita's BP, Arjun's spending), keeps that
user's state, and answers in that user's language. Each one is plain Python in `fixtures/personas/abilities/<user>/`,
checked by the same static check and sandbox as compiled skills, and tested against its cases, held-out ones
included, every time a backend starts. Only an ability that passes them all is `active`.

Users are keyed by their plain profile id (`ramesh`, `sunita`, `arjun`), never by a phone's device id or a hash,
so every backend that loads the same fixtures shows the same people. The phone names the active profile in the
`X-Saathi-Profile` header. State lives in the local database (every read) and is mirrored to that user's Meko
datapack in the background (`app.memory`), like every other memory.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import re
import sqlite3
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from datetime import time as dt_time
from functools import lru_cache
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from app.config import get_settings
from app.skills.sandbox import Sandbox

log = logging.getLogger("saathi.abilities")
ENTRY = "run"
IST = ZoneInfo("Asia/Kolkata")
PROFILE_HEADER = "X-Saathi-Profile"
_PROFILE = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS ability_state (
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    state TEXT NOT NULL,
    runs INTEGER NOT NULL DEFAULT 0,
    updated_at REAL NOT NULL,
    PRIMARY KEY (user_id, name)
);
"""


def user_key(profile: str) -> str:
    """The memory id for a demo user: readable and the same on every backend."""
    return f"persona:{profile}"


@dataclass
class Ability:
    user: str
    name: str
    title: str
    description: str
    code: str
    sha256: str
    triggers: list[re.Pattern[str]]
    cases: list[dict[str, Any]]
    learned_from: dict[str, Any]
    seed_state: dict[str, Any]
    status: str = "untested"  # untested → active | degraded
    passed: int = 0
    last_error: str | None = None

    def matches(self, text: str) -> bool:
        return any(t.search(text) for t in self.triggers)

    def public(self, runs: int = 0) -> dict[str, Any]:
        held = [c for c in self.cases if c.get("held_out")]
        return {
            "name": self.name, "title": self.title, "description": self.description, "status": self.status,
            "tests": {"passed": self.passed, "total": len(self.cases), "held_out": len(held)},
            "learned_from": self.learned_from, "local_runs": runs, "sha256": self.sha256[:12],
            "last_error": self.last_error,
        }  # fmt: skip


@dataclass
class Persona:
    id: str
    name: str
    language: str
    conditions: list[str]
    quirks: list[str]
    facts: list[dict[str, str]]
    abilities: list[Ability] = field(default_factory=list)
    appointment: dict[str, Any] | None = None  # seeded as the doctor feature's remembered booking


def resolve_dates(value: Any, today: date) -> Any:
    """`days_ago: n` → `date`, `as_of_days_ago` → `as_of`, `date_days_ago` → `date`: seeds stay fresh on any day."""
    if isinstance(value, list):
        return [resolve_dates(v, today) for v in value]
    if not isinstance(value, dict):
        return value
    out: dict[str, Any] = {}
    for k, v in value.items():
        if k in ("days_ago", "date_days_ago"):
            out["date"] = (today - timedelta(days=int(v))).isoformat()
        elif k.endswith("_days_ago") and k not in ("first_seen_days_ago", "compiled_days_ago"):
            out[k.removesuffix("_days_ago")] = (today - timedelta(days=int(v))).isoformat()
        else:
            out[k] = resolve_dates(v, today)
    return out


def load_personas(root: Path) -> dict[str, Persona]:
    path = root / "personas" / "personas.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text("utf-8"))
    out: dict[str, Persona] = {}
    for p in data.get("personas", []):
        persona = Persona(p["id"], p["name"], p["language"], p.get("conditions", []), p.get("quirks", []),
                          p.get("facts", []), appointment=p.get("appointment"))  # fmt: skip
        for a in p.get("abilities", []):
            code = (root / "personas" / "abilities" / p["id"] / f"{a['name']}.py").read_text("utf-8")
            persona.abilities.append(Ability(
                user=p["id"], name=a["name"], title=a.get("title", a["name"]), description=a.get("description", ""),
                code=code, sha256=hashlib.sha256(code.encode()).hexdigest(),
                triggers=[re.compile(t, re.IGNORECASE) for t in a.get("triggers", [])],
                cases=a.get("cases", []), learned_from=a.get("learned_from", {}), seed_state=a.get("state", {}),
            ))  # fmt: skip
        out[persona.id] = persona
    return out


class Abilities:
    def __init__(self, fixtures: Path, db_path: Path, sandbox: Sandbox | None = None) -> None:
        self.personas = load_personas(fixtures)
        self.db_path = db_path
        self.sandbox = sandbox or Sandbox()
        self._verified = False
        self._lock = asyncio.Lock()
        with self._conn() as c:
            c.executescript(SCHEMA)

    @contextlib.contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    # ------------------------------------------------------------------ lookup

    def persona(self, profile: str | None) -> Persona | None:
        profile = (profile or "").strip().lower()
        return self.personas.get(profile) if _PROFILE.match(profile) else None

    def ability(self, profile: str, name: str) -> Ability | None:
        p = self.persona(profile)
        return next((a for a in p.abilities if a.name == name), None) if p else None

    def match(self, profile: str | None, text: str) -> Ability | None:
        """The user's active ability for this request, or None (the LLM answers, as before)."""
        p = self.persona(profile)
        if p is None:
            return None
        return next((a for a in p.abilities if a.status == "active" and a.matches(text)), None)

    # ------------------------------------------------------------------ state

    def state(self, profile: str, name: str) -> tuple[dict[str, Any], int]:
        with self._conn() as c:
            row = c.execute(
                "SELECT state, runs FROM ability_state WHERE user_id=? AND name=?", (profile, name)
            ).fetchone()
        return (json.loads(row["state"]), int(row["runs"])) if row else ({}, 0)

    def _save(self, profile: str, name: str, state: dict[str, Any], ran: bool) -> None:
        with self._conn() as c:
            c.execute(
                "INSERT INTO ability_state(user_id,name,state,runs,updated_at) VALUES (?,?,?,?,?) "
                "ON CONFLICT(user_id,name) DO UPDATE SET state=excluded.state, runs=runs+?, updated_at=excluded.updated_at",
                (profile, name, json.dumps(state, ensure_ascii=False), int(ran), time.time(), int(ran)),
            )

    # ------------------------------------------------------------------ startup

    async def seed(self) -> None:
        """Fresh state and facts for every persona that has none yet (so a demo's own entries survive restarts)."""
        from app.memory import get_memory

        memory = get_memory()
        today = date.today()
        for p in self.personas.values():
            user = user_key(p.id)
            for a in p.abilities:
                if not self._has_state(p.id, a.name):
                    self._save(p.id, a.name, resolve_dates(a.seed_state, today), ran=False)
            # Every start: local copies are refreshed, and Meko gets whatever it is missing (seed() checks the
            # datapack once), so a Meko outage on one start is made up on the next.
            seed = getattr(memory, "seed", memory.remember)
            for f in p.facts:
                await seed(user, f["text"], f["topic"], f.get("key", ""), source="persona_seed",
                           agent=f.get("agent", "saathi-voice"), learned_from=f.get("learned_from", ""))  # fmt: skip
            await self._seed_appointment(p, user, memory)

    async def _seed_appointment(self, p: Persona, user: str, memory: Any) -> None:
        """The persona's upcoming booking, as the care agent would have stored it; never over a newer real one."""
        from app.care import remembered

        a = p.appointment
        if not a or await remembered.next_appointment(user):
            return
        hour, minute = (int(x) for x in str(a["at"]).split(":"))
        start = datetime.combine(date.today() + timedelta(days=int(a["in_days"])), dt_time(hour, minute), IST)
        text = (f"Appointment with {a['doctor']} ({a['specialty']}) at {a['clinic']}, {a['address']}, "
                f"{start.strftime('%a %d %b, %H:%M')}")  # fmt: skip
        meta = {
            k: a.get(k) for k in ("doctor", "specialty", "clinic", "address", "lat", "lng", "learned_from")
        }
        seed = getattr(memory, "seed", memory.remember)  # Meko gets it once, whichever backend seeds first
        await seed(user, text, remembered.KIND, remembered.KEY, agent=remembered.AGENT,
                              start_iso=start.isoformat(), booking_ref="", phone="", source="persona_seed",
                              **meta)  # fmt: skip

    def _has_state(self, profile: str, name: str) -> bool:
        with self._conn() as c:
            return bool(
                c.execute(
                    "SELECT 1 FROM ability_state WHERE user_id=? AND name=?", (profile, name)
                ).fetchone()
            )

    async def reset(self, profile: str) -> None:
        """Back to the seeded state (before a demo)."""
        with self._conn() as c:
            c.execute("DELETE FROM ability_state WHERE user_id=?", (profile,))
        await self.seed()

    async def verify(self) -> None:
        """Runs every ability's cases in the sandbox; only an ability that passes all of them goes active."""
        async with self._lock:
            for p in self.personas.values():
                for a in p.abilities:
                    results = await self.sandbox.run(a.code, ENTRY, [c["input"] for c in a.cases])
                    a.passed = sum(_case_ok(c, r) for c, r in zip(a.cases, results, strict=True))
                    failed = next(
                        (r for c, r in zip(a.cases, results, strict=True) if not _case_ok(c, r)), None
                    )
                    a.status = "active" if a.passed == len(a.cases) and a.cases else "degraded"
                    a.last_error = (
                        None if failed is None else str(failed.get("error") or "wrong answer")[:200]
                    )
                    log.info("ability %s/%s %s (%d/%d)", p.id, a.name, a.status, a.passed, len(a.cases))
            self._verified = True

    # ------------------------------------------------------------------ run

    async def run(self, profile: str, ability: Ability, text: str) -> dict[str, Any] | None:
        """Runs the compiled code on the user's request and state. None when it fails (the LLM answers instead)."""
        if ability.sha256 != hashlib.sha256(ability.code.encode()).hexdigest():
            return None
        state, _ = self.state(profile, ability.name)
        job = {"text": text[:500], "state": state, "today": date.today().isoformat()}
        started = time.perf_counter()
        result = (await self.sandbox.run(ability.code, ENTRY, [job]))[0]
        value = result.get("value") if result.get("ok") else None
        if not isinstance(value, dict) or not isinstance(value.get("say"), str):
            ability.status, ability.last_error = "degraded", str(result.get("error") or "bad output")[:200]
            log.warning("ability %s/%s degraded: %s", profile, ability.name, ability.last_error)
            return None
        new_state = value.get("state") if isinstance(value.get("state"), dict) else state
        self._save(profile, ability.name, new_state, ran=True)
        _mirror(profile, ability.name, new_state)
        log.info("ability %s/%s ran in %d ms", profile, ability.name, (time.perf_counter() - started) * 1000)
        return {"say": value["say"], "urgent": bool(value.get("urgent")), "ability": ability.name}

    def listing(self, profile: str) -> dict[str, Any] | None:
        p = self.persona(profile)
        if p is None:
            return None
        return {
            "id": p.id, "name": p.name, "language": p.language, "conditions": p.conditions, "quirks": p.quirks,
            "abilities": [a.public(self.state(p.id, a.name)[1]) for a in p.abilities],
        }  # fmt: skip


def _case_ok(case: dict[str, Any], result: dict[str, Any]) -> bool:
    value = result.get("value") if result.get("ok") else None
    if not isinstance(value, dict) or not isinstance(value.get("say"), str):
        return False
    return all(e in value["say"] for e in case.get("expect", [])) and bool(value.get("urgent")) == bool(
        case.get("urgent", False)
    )


def _mirror(profile: str, name: str, state: dict[str, Any]) -> None:
    """The new state to the user's Meko datapack, off the request path (no-op without MEKO_API_KEY)."""
    from app.memory import get_memory

    mirror = getattr(get_memory(), "mirror_state", None)
    if mirror is not None:
        mirror(user_key(profile), name, state)


@lru_cache
def get_abilities() -> Abilities:
    settings = get_settings()
    return Abilities(settings.fixtures_dir, settings.database_path)
