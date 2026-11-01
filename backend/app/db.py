"""SQLite storage: devices, family contacts, alert log, link cache. Stdlib only."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    user_name TEXT NOT NULL DEFAULT 'Vaani user',
    contacts_json TEXT NOT NULL DEFAULT '[]',
    check_time TEXT,
    last_heartbeat_ms INTEGER,
    last_alert_date TEXT
);
CREATE TABLE IF NOT EXISTS device_keys (
    device_id TEXT PRIMARY KEY,
    key_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    text TEXT NOT NULL,
    sent_to TEXT NOT NULL,
    created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS link_cache (
    url_hash TEXT PRIMARY KEY,
    result_json TEXT NOT NULL,
    created_ms INTEGER NOT NULL
);
"""


@dataclass
class Device:
    device_id: str
    user_name: str
    contacts: list[dict[str, str]]
    check_time: str | None
    last_heartbeat_ms: int | None
    last_alert_date: str | None


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()
        path.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.executescript(_SCHEMA)

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            conn = sqlite3.connect(self.path)
            conn.row_factory = sqlite3.Row
            try:
                yield conn
                conn.commit()
            finally:
                conn.close()

    # ------------------------------------------------------------------ devices
    def _ensure(self, conn: sqlite3.Connection, device_id: str) -> None:
        conn.execute("INSERT OR IGNORE INTO devices (device_id) VALUES (?)", (device_id,))

    def heartbeat(self, device_id: str, ts_ms: int) -> None:
        with self._conn() as conn:
            self._ensure(conn, device_id)
            conn.execute(
                "UPDATE devices SET last_heartbeat_ms = MAX(COALESCE(last_heartbeat_ms, 0), ?) "
                "WHERE device_id = ?",
                (ts_ms, device_id),
            )

    def configure(
        self, device_id: str, user_name: str, contacts: list[dict[str, str]], check_time: str
    ) -> None:
        with self._conn() as conn:
            self._ensure(conn, device_id)
            conn.execute(
                "UPDATE devices SET user_name = ?, contacts_json = ?, check_time = ? WHERE device_id = ?",
                (user_name, json.dumps(contacts), check_time, device_id),
            )

    def mark_alerted(self, device_id: str, day_iso: str) -> None:
        with self._conn() as conn:
            conn.execute("UPDATE devices SET last_alert_date = ? WHERE device_id = ?", (day_iso, device_id))

    def device_key_hash(self, device_id: str) -> str | None:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT key_hash FROM device_keys WHERE device_id = ?", (device_id,)
            ).fetchone()
        return row["key_hash"] if row else None

    def claim_device_key(self, device_id: str, key_hash: str) -> str:
        """First key wins (trust on first use); returns the stored hash."""
        with self._conn() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO device_keys (device_id, key_hash) VALUES (?, ?)", (device_id, key_hash)
            )
            row = conn.execute(
                "SELECT key_hash FROM device_keys WHERE device_id = ?", (device_id,)
            ).fetchone()
        return str(row["key_hash"])

    def get_device(self, device_id: str) -> Device | None:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        return _device(row) if row else None

    def all_devices(self) -> list[Device]:
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM devices").fetchall()
        return [_device(r) for r in rows]

    # ------------------------------------------------------------------ alerts
    def log_alert(self, device_id: str, kind: str, text: str, sent_to: list[str]) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO alerts (device_id, kind, text, sent_to, created_ms) VALUES (?, ?, ?, ?, ?)",
                (device_id, kind, text, json.dumps(sent_to), int(time.time() * 1000)),
            )

    def alerts(self, device_id: str | None = None) -> list[dict[str, Any]]:
        query, args = "SELECT * FROM alerts", ()
        if device_id:
            query, args = query + " WHERE device_id = ?", (device_id,)
        with self._conn() as conn:
            return [dict(r) for r in conn.execute(query + " ORDER BY id", args).fetchall()]

    # ------------------------------------------------------------------ link cache
    def cache_get(self, url_hash: str, max_age_s: int) -> dict[str, Any] | None:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM link_cache WHERE url_hash = ?", (url_hash,)).fetchone()
        if not row or time.time() * 1000 - row["created_ms"] > max_age_s * 1000:
            return None
        return json.loads(row["result_json"])

    def cache_put(self, url_hash: str, result: dict[str, Any]) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO link_cache (url_hash, result_json, created_ms) VALUES (?, ?, ?)",
                (url_hash, json.dumps(result), int(time.time() * 1000)),
            )


def _device(row: sqlite3.Row) -> Device:
    return Device(
        device_id=row["device_id"],
        user_name=row["user_name"],
        contacts=json.loads(row["contacts_json"] or "[]"),
        check_time=row["check_time"],
        last_heartbeat_ms=row["last_heartbeat_ms"],
        last_alert_date=row["last_alert_date"],
    )
