"""The doctor intake as a live conversation: Gemini relays what was said, triage decides (docs/CARE.md)."""

from __future__ import annotations

import asyncio

import pytest
from starlette.websockets import WebSocketDisconnect

from app.care.session import CARE
from app.routers.voice_live import Relay
from tests.conftest import DEVICE


def _relay(monkeypatch):  # type: ignore[no-untyped-def]
    care = CARE.create(DEVICE)
    relay = Relay(ws=None, device=DEVICE, care=care)  # type: ignore[arg-type]
    sent: list[tuple[str, dict]] = []

    async def phone_tool(name, args):  # type: ignore[no-untyped-def]
        sent.append((name, args))
        return {"ok": True}

    monkeypatch.setattr(relay, "phone_tool", phone_tool)
    return relay, care, sent


def test_answers_get_the_next_question(client, monkeypatch) -> None:  # noqa: ANN001
    relay, care, sent = _relay(monkeypatch)
    out = asyncio.run(relay.call_tool("intake_turn", {"said": "I have had a fever since yesterday"}))
    assert out["question"] and care.pending_question == out["question"]
    assert sent == []  # nothing for the phone until the intake is done
    assert (
        asyncio.run(relay.call_tool("remember", {"fact": "x"}))["ok"] is False
    )  # only intake_turn in care mode


def test_red_flag_ends_the_intake_and_hands_the_note_to_the_phone(client, monkeypatch) -> None:  # noqa: ANN001
    relay, care, sent = _relay(monkeypatch)
    out = asyncio.run(relay.call_tool("intake_turn", {"said": "Papa behosh ho gaye, saans nahi le rahe"}))
    assert out == {"done": True, "route": "A"}
    ((name, args),) = sent
    assert name == "care_done" and args["route"] == "A"
    assert "EMERGENCY ASSESSMENT" in args["note"]["note_text"]


def test_unknown_care_session_is_refused(client) -> None:  # noqa: ANN001
    with pytest.raises(WebSocketDisconnect) as exc:
        with client.websocket_connect(f"/live/voice?device={DEVICE}&care=s_nope") as ws:
            ws.receive_text()
    assert exc.value.code == 4404
