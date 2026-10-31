"""Speed fixes: a stalled model is overtaken by its fallback (hedging); care events long-poll."""

from __future__ import annotations

import asyncio
import time
from types import SimpleNamespace

import pytest

from app.care.session import CareSession
from app.llm import do_inference, gemini_client


def _client(monkeypatch: pytest.MonkeyPatch, delays: dict[str, float], fail: set[str] = frozenset()) -> gemini_client.GeminiClient:
    async def fake(model, system, contents, schema, temperature):
        await asyncio.sleep(delays[model])
        if model in fail:
            raise do_inference.DoError("503 down")
        return f"answer from {model}", SimpleNamespace(prompt_token_count=1, candidates_token_count=1)

    monkeypatch.setattr(do_inference, "is_do_model", lambda m: True)
    monkeypatch.setattr(do_inference, "generate", fake)
    c = gemini_client.GeminiClient.__new__(gemini_client.GeminiClient)
    c.settings = SimpleNamespace(route=lambda name: {"model": "slow", "fallback": "fast", "timeout_s": 10, "hedge_s": 0.2})
    return c


async def test_stalled_model_is_overtaken_by_its_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    c = _client(monkeypatch, {"slow": 5.0, "fast": 0.05})
    started = time.perf_counter()
    assert await c._generate("r", "sys", ["hi"], None) == "answer from fast"
    assert time.perf_counter() - started < 1.0


async def test_quick_primary_needs_no_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    c = _client(monkeypatch, {"slow": 0.05, "fast": 0.05})
    assert await c._generate("r", "sys", ["hi"], None) == "answer from slow"


async def test_failed_primary_starts_the_fallback_at_once(monkeypatch: pytest.MonkeyPatch) -> None:
    c = _client(monkeypatch, {"slow": 0.01, "fast": 0.05}, fail={"slow"})
    started = time.perf_counter()
    assert await c._generate("r", "sys", ["hi"], None) == "answer from fast"
    assert time.perf_counter() - started < 0.2


async def test_care_events_long_poll() -> None:
    s = CareSession(id="s_test", device="d")
    asyncio.get_running_loop().call_later(0.2, lambda: s.emit("hospitals_found", count=3))
    started = time.perf_counter()
    events = await s.wait_events(0, 5)
    assert [e["type"] for e in events] == ["hospitals_found"]
    assert time.perf_counter() - started < 1.0
    assert await s.wait_events(1, 0) == []
