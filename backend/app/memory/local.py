"""Local memory: an elder's facts by topic, and per-run agent state checkpoints.

Facts are kept in the words and script the elder used (English, Hindi or Hinglish); only `topic` and `key` are
English labels, so nothing here depends on the language. An elder has tens of facts, not thousands, so reads
return all of them (newest first) instead of searching, which keyword search could not do across scripts anyway.
"""

from __future__ import annotations

import contextlib
import json
import sqlite3
import time
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL,           -- topic: name, home, family, doctor, health, preference, routine, other
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

MAX_FACTS = 60  # per read; far more than an elder tells Saathi, small enough for any prompt


def norm_key(key: str | None) -> str:
    return " ".join((key or "").lower().split())[:40]


STEP_KEEP_S = 7 * 24 * 3600


class LocalMemory:
    name = "local"

    def __init__(self, path: Path) -> None:
        self.path = path
        with self._conn() as c:
            c.executescript(SCHEMA)
            cols = {r["name"] for r in c.execute("PRAGMA table_info(memories)")}
            if "key" not in cols:  # databases from before topics and keys
                c.execute("ALTER TABLE memories ADD COLUMN key TEXT NOT NULL DEFAULT ''")
            c.execute("CREATE INDEX IF NOT EXISTS memories_key ON memories(device_id, kind, key)")

    @contextlib.contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    async def remember(self, device: str, text: str, kind: str = "other", key: str = "", **meta: Any) -> None:
        """Store a fact. With a key, it replaces the elder's earlier fact on the same topic and key."""
        text, key = text.strip(), norm_key(key)
        if not text:
            return
        with self._conn() as c:
            if key:
                c.execute("DELETE FROM memories WHERE device_id=? AND kind=? AND key=?", (device, kind, key))
            elif c.execute(
                "SELECT 1 FROM memories WHERE device_id=? AND lower(text)=lower(?)", (device, text)
            ).fetchone():
                return
            c.execute(
                "INSERT INTO memories(device_id,kind,key,text,meta,ts) VALUES (?,?,?,?,?,?)",
                (device, kind, key, text, json.dumps(meta, ensure_ascii=False), time.time()),
            )

    async def forget(self, device: str, kind: str, key: str = "") -> int:
        """Delete one fact (topic + key), or every fact on a topic when no key is given. Returns how many."""
        key = norm_key(key)
        with self._conn() as c:
            if key:
                cur = c.execute(
                    "DELETE FROM memories WHERE device_id=? AND kind=? AND key=?", (device, kind, key)
                )
            else:
                cur = c.execute("DELETE FROM memories WHERE device_id=? AND kind=?", (device, kind))
            return cur.rowcount

    async def forget_steps(self, device: str) -> int:
        """Every saved screen-task step for this device (part of "forget everything")."""
        with self._conn() as c:
            return c.execute("DELETE FROM agent_state WHERE device_id=?", (device,)).rowcount

    async def facts(self, device: str, topics: Iterable[str] | None = None) -> list[tuple[str, str]]:
        """(topic, text) for the elder's facts, newest first, optionally only some topics."""
        wanted = set(topics) if topics is not None else None
        with self._conn() as c:
            rows = c.execute(
                "SELECT kind, text FROM memories WHERE device_id=? ORDER BY ts DESC, id DESC", (device,)
            ).fetchall()
        out = [(r["kind"], r["text"]) for r in rows if wanted is None or r["kind"] in wanted]
        return out[:MAX_FACTS]

    async def entries(self, device: str) -> list[dict[str, Any]]:
        """Every fact with its key, metadata (agent, learned_from, …) and time, newest first."""
        with self._conn() as c:
            rows = c.execute(
                "SELECT kind, key, text, meta, ts FROM memories WHERE device_id=? ORDER BY ts DESC, id DESC",
                (device,),
            ).fetchall()
        return [{"topic": r["kind"], "key": r["key"], "text": r["text"], "meta": json.loads(r["meta"] or "{}"),
                 "ts": r["ts"]} for r in rows][:MAX_FACTS]  # fmt: skip

    async def seed(self, device: str, text: str, kind: str, key: str = "", **meta: Any) -> None:
        await self.remember(device, text, kind, key, **meta)

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        with self._conn() as c:
            # Step history only helps resume a task; nothing needs it after a week.
            c.execute("DELETE FROM agent_state WHERE ts < ?", (time.time() - STEP_KEEP_S,))
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

    def take_ids(self, prefix: str) -> list[str]:
        """Remove and return every stored id whose key starts with `prefix`."""
        pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        with self._conn() as c:
            rows = c.execute(
                "SELECT value FROM memory_ids WHERE key LIKE ? ESCAPE '\\'", (pattern,)
            ).fetchall()
            c.execute("DELETE FROM memory_ids WHERE key LIKE ? ESCAPE '\\'", (pattern,))
        return [r[0] for r in rows]

    async def health(self) -> dict[str, Any]:
        with self._conn() as c:
            n = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        return {"ok": True, "memories": n}
