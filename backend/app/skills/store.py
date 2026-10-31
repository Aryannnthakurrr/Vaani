"""SQLite persistence for the learning loop: agent runs (training traces), skills, and their usage counters."""

from __future__ import annotations

import contextlib
import hashlib
import json
import sqlite3
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS skill_runs (
    run_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    task TEXT NOT NULL,
    goal TEXT NOT NULL,
    mode TEXT NOT NULL,
    outcome TEXT,                 -- done | failed | cancelled | NULL (in progress)
    skill_id INTEGER,             -- skill that served (active) or shadowed this run
    skill_role TEXT,              -- active | shadow | NULL
    routine_pos INTEGER NOT NULL DEFAULT 0,
    misses INTEGER NOT NULL DEFAULT 0,
    shadow_ok INTEGER NOT NULL DEFAULT 1,
    settled INTEGER NOT NULL DEFAULT 0,    -- lifecycle counters applied (exactly once per run)
    llm_steps INTEGER NOT NULL DEFAULT 0,
    skill_steps INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    finished_at REAL
);
CREATE TABLE IF NOT EXISTS skill_run_steps (
    run_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    screen_text TEXT NOT NULL,
    action_json TEXT NOT NULL,
    handler TEXT NOT NULL,        -- llm | skill:<id> | llm-fallback
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS skills (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,           -- routine | parser
    name TEXT NOT NULL,           -- task name for routines
    version INTEGER NOT NULL,
    status TEXT NOT NULL,         -- candidate|tested|shadow|active|degraded|retired
    body TEXT NOT NULL DEFAULT '',
    sha256 TEXT NOT NULL DEFAULT '',
    trained_on TEXT NOT NULL DEFAULT '[]',
    held_out_pass REAL,
    shadow_matches INTEGER NOT NULL DEFAULT 0,
    runs INTEGER NOT NULL DEFAULT 0,
    recent TEXT NOT NULL DEFAULT '[]',   -- rolling window of 1/0 run results (active)
    last_error TEXT,
    approved_by TEXT,
    tokens_spent INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL,
    UNIQUE(kind, name, version)
);
CREATE TABLE IF NOT EXISTS skill_samples (   -- input/answer pairs from the LLM: training data for code skills
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,           -- recogniser name, e.g. intent
    input TEXT NOT NULL,
    output TEXT NOT NULL,         -- JSON the LLM answered
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS skill_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id INTEGER,
    event TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '{}',
    ts REAL NOT NULL
);
"""

LIVE = ("shadow", "active")


@dataclass
class Skill:
    id: int
    kind: str
    name: str
    version: int
    status: str
    body: str
    sha256: str
    trained_on: list[str]
    held_out_pass: float | None
    shadow_matches: int
    runs: int
    recent: list[int]
    last_error: str | None
    approved_by: str | None
    tokens_spent: int

    def verify(self) -> bool:
        return hashlib.sha256(self.body.encode()).hexdigest() == self.sha256


def _skill(r: sqlite3.Row) -> Skill:
    return Skill(
        r["id"], r["kind"], r["name"], r["version"], r["status"], r["body"], r["sha256"],
        json.loads(r["trained_on"]), r["held_out_pass"], r["shadow_matches"], r["runs"],
        json.loads(r["recent"]), r["last_error"], r["approved_by"], r["tokens_spent"],
    )  # fmt: skip


class SkillStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        with self._conn() as c:
            c.executescript(SCHEMA)

    @contextlib.contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    # ------------------------------------------------------------------ runs
    def run(self, run_id: str) -> sqlite3.Row | None:
        with self._conn() as c:
            return c.execute("SELECT * FROM skill_runs WHERE run_id=?", (run_id,)).fetchone()

    def start_run(self, run_id: str, device: str, task: str, goal: str, mode: str) -> sqlite3.Row:
        with self._conn() as c:
            c.execute(
                "INSERT OR IGNORE INTO skill_runs(run_id,device_id,task,goal,mode,created_at) VALUES (?,?,?,?,?,?)",
                (run_id, device, task, goal, mode, time.time()),
            )
            return c.execute("SELECT * FROM skill_runs WHERE run_id=?", (run_id,)).fetchone()

    def update_run(self, run_id: str, **fields: Any) -> None:
        if not fields:
            return
        cols = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as c:
            c.execute(f"UPDATE skill_runs SET {cols} WHERE run_id=?", (*fields.values(), run_id))

    def add_step(self, run_id: str, screen_text: str, action: dict[str, Any], handler: str) -> None:
        with self._conn() as c:
            seq = c.execute("SELECT COUNT(*) FROM skill_run_steps WHERE run_id=?", (run_id,)).fetchone()[0]
            c.execute(
                "INSERT INTO skill_run_steps VALUES (?,?,?,?,?)",
                (run_id, seq, screen_text, json.dumps(action, ensure_ascii=False), handler),
            )

    def steps(self, run_id: str) -> list[dict[str, Any]]:
        with self._conn() as c:
            rows = c.execute(
                "SELECT screen_text, action_json, handler FROM skill_run_steps WHERE run_id=? ORDER BY seq",
                (run_id,),
            ).fetchall()
        return [{"screen": r[0], "action": json.loads(r[1]), "handler": r[2]} for r in rows]

    def successful_runs(self, task: str, limit: int = 12) -> list[sqlite3.Row]:
        """Completed ACT runs of this task that the LLM drove end to end: the compiler's training data."""
        with self._conn() as c:
            return c.execute(
                "SELECT * FROM skill_runs WHERE task=? AND mode='ACT' AND outcome='done' AND "
                "(skill_role IS NULL OR skill_role='shadow') ORDER BY created_at DESC LIMIT ?",
                (task, limit),
            ).fetchall()

    def tasks_with_runs(self, min_runs: int) -> list[tuple[str, int]]:
        with self._conn() as c:
            rows = c.execute(
                "SELECT task, COUNT(*) n FROM skill_runs WHERE mode='ACT' AND outcome='done' AND "
                "(skill_role IS NULL OR skill_role='shadow') GROUP BY task HAVING n>=?",
                (min_runs,),
            ).fetchall()
        return [(r["task"], r["n"]) for r in rows]

    def purge_old_runs(self, days: int = 30) -> int:
        cutoff = time.time() - days * 86400
        with self._conn() as c:
            ids = [r[0] for r in c.execute("SELECT run_id FROM skill_runs WHERE created_at<?", (cutoff,))]
            c.executemany("DELETE FROM skill_run_steps WHERE run_id=?", [(i,) for i in ids])
            c.execute("DELETE FROM skill_runs WHERE created_at<?", (cutoff,))
            c.execute("DELETE FROM skill_samples WHERE created_at<?", (cutoff,))
        return len(ids)

    # ------------------------------------------------------------------ samples (code skills)
    def add_sample(self, kind: str, input_text: str, output: dict[str, Any]) -> None:
        with self._conn() as c:
            c.execute(
                "INSERT INTO skill_samples(kind,input,output,created_at) VALUES (?,?,?,?)",
                (kind, input_text, json.dumps(output, ensure_ascii=False), time.time()),
            )

    def samples(self, kind: str, limit: int = 400) -> list[tuple[str, dict[str, Any]]]:
        with self._conn() as c:
            rows = c.execute(
                "SELECT input, output FROM skill_samples WHERE kind=? ORDER BY id DESC LIMIT ?", (kind, limit)
            ).fetchall()
        return [(r[0], json.loads(r[1])) for r in rows]

    def sample_count(self, kind: str) -> int:
        with self._conn() as c:
            return int(c.execute("SELECT COUNT(*) FROM skill_samples WHERE kind=?", (kind,)).fetchone()[0])

    # ------------------------------------------------------------------ skills
    def live_skill(self, kind: str, name: str) -> Skill | None:
        """The newest shadow/active version for this task (active preferred)."""
        with self._conn() as c:
            r = c.execute(
                "SELECT * FROM skills WHERE kind=? AND name=? AND status IN ('shadow','active') "
                "ORDER BY status='active' DESC, version DESC LIMIT 1",
                (kind, name),
            ).fetchone()
        return _skill(r) if r else None

    def latest(self, kind: str, name: str) -> Skill | None:
        with self._conn() as c:
            r = c.execute(
                "SELECT * FROM skills WHERE kind=? AND name=? ORDER BY version DESC LIMIT 1", (kind, name)
            ).fetchone()
        return _skill(r) if r else None

    def get(self, skill_id: int) -> Skill | None:
        with self._conn() as c:
            r = c.execute("SELECT * FROM skills WHERE id=?", (skill_id,)).fetchone()
        return _skill(r) if r else None

    def all(self) -> list[Skill]:
        with self._conn() as c:
            return [_skill(r) for r in c.execute("SELECT * FROM skills ORDER BY name, version")]

    def create(self, kind: str, name: str, version: int, status: str, body: str, trained_on: list[str],
               held_out_pass: float | None, tokens: int, last_error: str | None = None) -> int:  # fmt: skip
        now = time.time()
        sha = hashlib.sha256(body.encode()).hexdigest() if body else ""
        with self._conn() as c:
            cur = c.execute(
                "INSERT INTO skills(kind,name,version,status,body,sha256,trained_on,held_out_pass,tokens_spent,"
                "last_error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (kind, name, version, status, body, sha, json.dumps(trained_on), held_out_pass, tokens,
                 last_error, now, now),
            )  # fmt: skip
            return int(cur.lastrowid or 0)

    def set(self, skill_id: int, **fields: Any) -> None:
        for k in ("recent", "trained_on"):
            if k in fields and not isinstance(fields[k], str):
                fields[k] = json.dumps(fields[k])
        fields["updated_at"] = time.time()
        cols = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as c:
            c.execute(f"UPDATE skills SET {cols} WHERE id=?", (*fields.values(), skill_id))

    def retire_older(self, kind: str, name: str, keep_version: int) -> None:
        with self._conn() as c:
            c.execute(
                "UPDATE skills SET status='retired', updated_at=? WHERE kind=? AND name=? AND version<? "
                "AND status!='retired'",
                (time.time(), kind, name, keep_version),
            )

    def event(self, skill_id: int | None, event: str, **detail: Any) -> None:
        with self._conn() as c:
            c.execute(
                "INSERT INTO skill_events(skill_id,event,detail,ts) VALUES (?,?,?,?)",
                (skill_id, event, json.dumps(detail, ensure_ascii=False), time.time()),
            )

    def events(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._conn() as c:
            rows = c.execute("SELECT * FROM skill_events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [{**dict(r), "detail": json.loads(r["detail"])} for r in rows]

    # ------------------------------------------------------------------ stats
    def stats(self) -> dict[str, Any]:
        with self._conn() as c:
            llm, skill = c.execute(
                "SELECT COALESCE(SUM(llm_steps),0), COALESCE(SUM(skill_steps),0) FROM skill_runs"
            ).fetchone()
            by_status = {r[0]: r[1] for r in c.execute("SELECT status, COUNT(*) FROM skills GROUP BY status")}
            spent = c.execute("SELECT COALESCE(SUM(tokens_spent),0) FROM skills").fetchone()[0]
        return {"llm_steps": llm, "skill_steps": skill, "skills": by_status, "compile_tokens": spent}
