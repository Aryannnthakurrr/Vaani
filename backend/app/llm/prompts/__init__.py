"""Prompt templates. Placeholders use {name} and are filled with str.replace (prompts contain JSON braces)."""

from __future__ import annotations

from datetime import date
from functools import cache
from pathlib import Path

_DIR = Path(__file__).resolve().parent


@cache
def _raw(name: str) -> str:
    return (_DIR / f"{name}.md").read_text("utf-8")


def render(name: str, **values: str) -> str:
    text = _raw(name).replace("{safety}", _raw("_safety")).replace("{language}", _raw("_language"))
    values.setdefault("today", date.today().isoformat())
    for key, value in values.items():
        text = text.replace("{" + key + "}", value)
    return text
