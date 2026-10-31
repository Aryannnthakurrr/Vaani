"""Local memory: SQLite facts with keyword search, and per-run agent state checkpoints."""

from __future__ import annotations

import contextlib
import json
import re
import sqlite3
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    text TEXT NOT NULL,
    meta TEXT NOT NULL DEFAULT '{}',
    ts REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS memories_device ON memories(device_id, ts);
CREATE TABLE IF NOT EXISTS agent_state (
    run_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    device_id TEXT NOT NULL,
    goal TEXT NOT NULL,
    step TEXT NOT NULL,
    ts REAL NOT NULL,
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS memory_ids (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""

_WORD = re.compile(r"[\wऀ-ॿ]+")
STOP = {
    "the",
    "a",
    "an",
    "to",
    "of",
    "and",
    "is",
    "my",
    "me",
    "i",
    "for",
    "in",
    "on",
    "ka",
    "ki",
    "ke",
    "hai",
    "ko",
}


class LocalMemory:
    name = "local"

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

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None:
        text = text.strip()
        if not text:
            return
        with self._conn() as c:
            dup = c.execute(
                "SELECT 1 FROM memories WHERE device_id=? AND lower(text)=lower(?)", (device, text)
            )
            if dup.fetchone():
                return
            c.execute(
                "INSERT INTO memories(device_id,kind,text,meta,ts) VALUES (?,?,?,?,?)",
                (device, kind, text, json.dumps(meta, ensure_ascii=False), time.time()),
            )

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        words = {w for w in _WORD.findall(query.lower()) if w not in STOP and len(w) > 1}
        with self._conn() as c:
            rows = c.execute(
                "SELECT text, kind, ts FROM memories WHERE device_id=? ORDER BY ts DESC LIMIT 200", (device,)
            ).fetchall()
        scored = []
        for r in rows:
            overlap = len(words & set(_WORD.findall(r["text"].lower())))
            pref = 1 if r["kind"] in {"fact", "preference"} else 0
            if overlap or not words:
                scored.append((overlap, pref, r["ts"], r["text"]))
        scored.sort(reverse=True)
        if not scored:  # nothing matches: still give the few most recent stable facts
            scored = [(0, 1, r["ts"], r["text"]) for r in rows if r["kind"] in {"fact", "preference"}][:limit]
        return [t for *_, t in scored[:limit]]

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        with self._conn() as c:
            seq = c.execute("SELECT COUNT(*) FROM agent_state WHERE run_id=?", (run_id,)).fetchone()[0]
            c.execute(
                "INSERT INTO agent_state VALUES (?,?,?,?,?,?)",
                (run_id, seq, device, goal, json.dumps(step, ensure_ascii=False), time.time()),
            )

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        with self._conn() as c:
            rows = c.execute(
                "SELECT step FROM agent_state WHERE run_id=? AND device_id=? ORDER BY seq", (run_id, device)
            ).fetchall()
        return [json.loads(r[0]) for r in rows]

    def get_id(self, key: str) -> str | None:
        with self._conn() as c:
            row = c.execute("SELECT value FROM memory_ids WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_id(self, key: str, value: str) -> None:
        with self._conn() as c:
            c.execute("INSERT OR REPLACE INTO memory_ids VALUES (?,?)", (key, value))

    async def health(self) -> dict[str, Any]:
        with self._conn() as c:
            n = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        return {"ok": True, "memories": n}
