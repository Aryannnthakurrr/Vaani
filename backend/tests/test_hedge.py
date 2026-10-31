"""Model calls must not make the user wait on one slow or overloaded model.

Measured 2026-10-31: the same model took ~1 s or ~20 s within minutes, and returned 503s. These tests go through
`GeminiClient._generate` with the real `agent_step` route config and a fake Gemini whose models answer slowly or fail.
"""

from __future__ import annotations

import asyncio
import time
from types import SimpleNamespace

import pytest

from app.config import get_settings
from app.llm.gemini_client import GeminiClient


def _client(behaviour: dict[str, tuple[float, str | Exception]]) -> tuple[GeminiClient, list[str]]:
    c = GeminiClient(get_settings())
    calls: list[str] = []

    async def generate_content(model, contents, config):  # noqa: ANN001, ANN202
        calls.append(model)
        delay, out = behaviour[model]
        await asyncio.sleep(delay)
        if isinstance(out, Exception):
            raise out
        return SimpleNamespace(text=out, usage_metadata=None)

    c.client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=generate_content)))
    return c, calls


def _models() -> tuple[str, str]:
    cfg = get_settings().route("agent_step")
    return cfg["model"], cfg["fallback"]


def _ask(c: GeminiClient) -> tuple[str, float]:
    started = time.perf_counter()
    text = asyncio.run(c._generate("agent_step", "system", ["screen"], None))
    return text, time.perf_counter() - started


def test_a_slow_primary_does_not_hold_up_the_step() -> None:
    primary, fallback = _models()
    c, _ = _client({primary: (8.0, "slow"), fallback: (0.1, "fast")})
    text, took = _ask(c)
    assert text == "fast"
    assert took < 4.0, f"waited {took:.1f}s on a slow model"


def test_a_fast_primary_answers_alone() -> None:
    primary, fallback = _models()
    c, calls = _client({primary: (0.05, "fast"), fallback: (0.05, "other")})
    assert _ask(c)[0] == "fast" and calls == [primary]


def test_an_overloaded_primary_falls_through_at_once() -> None:
    primary, fallback = _models()
    c, _ = _client({primary: (0.05, RuntimeError("503 UNAVAILABLE")), fallback: (0.1, "fast")})
    text, took = _ask(c)
    assert text == "fast" and took < 1.0


def test_a_real_error_is_raised() -> None:
    primary, fallback = _models()
    c, _ = _client({primary: (0.05, ValueError("400 bad request")), fallback: (0.05, "fast")})
    with pytest.raises(ValueError):
        _ask(c)
