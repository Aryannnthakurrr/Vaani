"""Shared FastAPI dependencies: device auth, settings, database, LLM client."""

from __future__ import annotations

from functools import lru_cache

from fastapi import Header, HTTPException

from app.config import Settings, get_settings
from app.db import Database
from app.llm.base import LLMClient, get_llm


def require_device(x_saathi_device: str | None = Header(default=None)) -> str:
    """Hackathon auth: a device UUID header. Production needs real auth (see README)."""
    if not x_saathi_device or len(x_saathi_device.strip()) < 8:
        raise HTTPException(status_code=401, detail="missing X-Saathi-Device header")
    return x_saathi_device.strip()


def settings_dep() -> Settings:
    return get_settings()


@lru_cache
def get_db() -> Database:
    return Database(get_settings().database_path)


def db_dep() -> Database:
    return get_db()


def llm_dep() -> LLMClient:
    return get_llm(get_settings())
