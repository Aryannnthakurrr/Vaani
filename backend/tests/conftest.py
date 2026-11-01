from __future__ import annotations

import json
import os
from collections.abc import Iterator
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
FIXTURES = REPO / "fixtures"
DEVICE = "test-device-0001"
HEADERS = {"X-Saathi-Device": DEVICE}


def serialize_screen(screen: dict) -> str:
    """Python twin of the Android serializer (docs/engineering/CONTRACTS.md format)."""
    lines = [
        f'package={screen["package"]} title="{screen.get("title", "")}" secure={str(screen.get("secure", False)).lower()}'
    ]
    for i, n in enumerate(screen["nodes"], start=1):
        parts = [f"[{i}]", n["cls"]]
        if n.get("text"):
            parts.append(f'"{n["text"]}"')
        if n.get("desc"):
            parts.append(f'desc="{n["desc"]}"')
        if n.get("hint"):
            parts.append(f'hint="{n["hint"]}"')
        for flag in ("clickable", "editable", "scrollable", "checkable", "checked", "focused"):
            if n.get(flag):
                parts.append(flag)
        b = n["bounds"]
        parts.append(f"bounds=({b[0]},{b[1]},{b[2]},{b[3]})")
        lines.append("  " * n.get("depth", 0) + " ".join(parts))
    return "\n".join(lines)


def load_screens(scenario: str) -> list[dict]:
    folder = FIXTURES / "screens" / scenario
    return [json.loads(p.read_text("utf-8")) for p in sorted(folder.glob("*.json"))]


@pytest.fixture()
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator:
    for key in (
        "GEMINI_API_KEY",
        "SARVAM_API_KEY",
        "TELEGRAM_BOT_TOKEN",
        "SAFE_BROWSING_API_KEY",
        "SANDBOX_ENABLED",
        "BOLNA_API_KEY",
        "BOLNA_AGENT_ID",
        "MEKO_API_KEY",
        "SKILL_SANDBOX",
    ):
        monkeypatch.setenv(key, "")
    monkeypatch.setenv("MOCK", "1")
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path / 'test.db'}")
    monkeypatch.setenv("FIXTURES_DIR", str(FIXTURES))

    from app import deps
    from app.abilities import get_abilities
    from app.config import get_settings
    from app.llm.base import reset_llm
    from app.memory import get_memory
    from app.skills import get_code_skills, get_engine

    get_settings.cache_clear()
    deps.get_db.cache_clear()
    get_memory.cache_clear()
    get_engine.cache_clear()
    get_code_skills.cache_clear()
    get_abilities.cache_clear()
    reset_llm()

    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        yield c
    get_settings.cache_clear()
    deps.get_db.cache_clear()
    get_memory.cache_clear()
    get_engine.cache_clear()
    get_code_skills.cache_clear()
    reset_llm()
    os.environ.pop("DATABASE_URL", None)
