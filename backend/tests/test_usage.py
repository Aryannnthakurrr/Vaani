"""Usage metering: per-device routes and bytes, model tokens, and cost only from configured prices."""

from __future__ import annotations

from types import SimpleNamespace

from app.usage import METER, Meter, cost
from tests.conftest import HEADERS


def test_requests_are_counted_per_route_template(client) -> None:
    METER.reset()
    for text in ("book a cab to Saket", "namaste"):
        client.post("/intent", json={"utterance": text}, headers=HEADERS)
    sid = client.post("/care/session", json={}, headers=HEADERS).json()["session_id"]
    client.get(f"/care/{sid}/events", headers=HEADERS)
    mine = client.get("/usage", headers=HEADERS).json()
    assert mine["routes"]["POST /intent"]["count"] == 2
    assert mine["routes"]["GET /care/{session_id}/events"]["count"] == 1  # the template, not the session id
    assert mine["totals"]["bytes_up"] > 0 and mine["totals"]["bytes_down"] > 0
    other = client.get("/usage", headers={"X-Saathi-Device": "someone-else-0002"}).json()
    assert "POST /intent" not in other["routes"]  # a device sees only its own numbers


def test_model_tokens_and_cost() -> None:
    m = Meter()
    m.llm(
        "gemini-3.5-flash-lite",
        SimpleNamespace(prompt_token_count=1200, candidates_token_count=80),
        device="d1",
    )
    m.llm(
        "gemini-3.5-flash-lite",
        SimpleNamespace(prompt_token_count=800, candidates_token_count=20),
        device="d1",
    )
    m.llm("gemini-3.5-flash", None, device="d2")
    snap = m.snapshot("d1")
    assert snap["models"]["gemini-3.5-flash-lite"] == {
        "calls": 2,
        "prompt_tokens": 2000,
        "output_tokens": 100,
    }
    assert snap["totals"]["tokens"] == 2100
    priced = cost(snap, {"gemini-3.5-flash-lite": {"input_per_m": 0.1, "output_per_m": 0.4}})
    assert priced["usd_total"] == round((2000 * 0.1 + 100 * 0.4) / 1e6, 6)
    unpriced = cost(snap, {"gemini-3.5-flash-lite": {"input_per_m": None, "output_per_m": None}})
    assert unpriced["usd_total"] == 0 and unpriced["unpriced"] == ["gemini-3.5-flash-lite"]
