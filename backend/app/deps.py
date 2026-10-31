"""Shared FastAPI dependencies: device auth, settings, database, LLM client."""

from __future__ import annotations

import hashlib
import hmac
from functools import lru_cache

from fastapi import Header, HTTPException

from app.config import Settings, get_settings
from app.db import Database
from app.llm.base import LLMClient, get_llm


def device_ok(device: str | None, key: str | None) -> bool:
    """Device id plus its install secret (X-Saathi-Key, 32 random bytes made on the phone's first launch).

    The first key a device sends is kept as a SHA-256 hash (trust on first use); after that every request must carry
    the same key, so knowing or guessing a device id is no longer enough to read its care events or memories. Only
    the hash is stored. A device that has never sent a key (older app builds) is accepted on its id alone.
    """
    if not device or len(device.strip()) < 8:
        return False
    stored = get_db().device_key_hash(device.strip())
    if key is None or not key.strip():
        return stored is None
    digest = hashlib.sha256(key.strip().encode()).hexdigest()
    if stored is None:
        stored = get_db().claim_device_key(device.strip(), digest)
    return hmac.compare_digest(stored, digest)


def require_device(
    x_saathi_device: str | None = Header(default=None), x_saathi_key: str | None = Header(default=None)
) -> str:
    """The calling device, checked against its install secret (see device_ok)."""
    if not x_saathi_device or len(x_saathi_device.strip()) < 8:
        raise HTTPException(status_code=401, detail="missing X-Saathi-Device header")
    if not device_ok(x_saathi_device, x_saathi_key):
        raise HTTPException(status_code=401, detail="wrong or missing X-Saathi-Key for this device")
    return x_saathi_device.strip()


def same_device(header_device: str, body_device: str) -> None:
    """Routes that also name a device in the body may act only on the caller's own device."""
    if header_device != body_device.strip():
        raise HTTPException(status_code=403, detail="device_id does not match the calling device")


def settings_dep() -> Settings:
    return get_settings()


@lru_cache
def get_db() -> Database:
    return Database(get_settings().database_path)


def db_dep() -> Database:
    return get_db()


def llm_dep() -> LLMClient:
    return get_llm(get_settings())
