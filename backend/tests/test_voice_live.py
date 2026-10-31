"""WS /live/voice in mock mode: transcripts, phone tool round trips, the remember guard, and memories in the prompt."""

from __future__ import annotations

import pytest

from app.routers.voice_live import VOICE_TOOLS, safe_fact
from tests.conftest import DEVICE


def _until(ws, kind: str, limit: int = 10) -> dict:
    for _ in range(limit):
        msg = ws.receive_json()
        if msg["type"] == kind:
            return msg
    raise AssertionError(f"no {kind} message")


def test_voice_text_turn_and_tool_round_trip(client):
    with client.websocket_connect(f"/live/voice?device={DEVICE}") as ws:
        ws.send_json({"type": "config", "reply_style": "hinglish"})
        ws.send_json({"type": "text", "text": "book a cab to Sector 18"})
        assert ws.receive_json() == {
            "type": "transcript",
            "role": "user",
            "text": "book a cab to Sector 18",
            "final": False,
        }
        tool = _until(ws, "tool")
        assert tool["name"] == "start_task"
        assert tool["args"] == {"task": "book_cab", "destination": "Sector 18"}
        ws.send_json({"type": "tool_result", "id": tool["id"], "result": {"ok": True, "status": "started"}})
        said = _until(ws, "transcript")
        while said["role"] != "saathi":
            said = _until(ws, "transcript")
        assert "cab" in said["text"].lower()
        ws.send_json({"type": "end"})


def test_voice_remember_goes_to_memory_and_into_the_next_prompt(client):
    import asyncio

    from app.memory import get_memory
    from app.routers.voice_live import system_prompt

    with client.websocket_connect(f"/live/voice?device={DEVICE}") as ws:
        ws.send_json({"type": "config", "reply_style": "en"})
        ws.send_json({"type": "text", "text": "remember my cardiologist is Dr. Mehta"})
        said = _until(ws, "transcript")
        while said["role"] != "saathi":
            said = _until(ws, "transcript")
        ws.send_json({"type": "end"})
    facts = asyncio.run(get_memory().recall(DEVICE, "doctor"))
    assert facts == ["my cardiologist is Dr. Mehta"]
    prompt = asyncio.run(system_prompt(DEVICE, "en"))
    assert "Dr. Mehta" in prompt and "{memories}" not in prompt


def test_voice_requires_device(client):
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect), client.websocket_connect("/live/voice") as ws:
        ws.receive_json()


@pytest.mark.parametrize(
    "fact,ok",
    [
        ("My daughter Priya lives in Pune", True),
        ("I prefer Uber Go", True),
        ("my UPI pin is 4321", False),
        ("OTP 482913", False),
        ("card 4111 1111 1111 1111", False),
        ("मेरा पासवर्ड abc है", False),
        ("x", False),
    ],
)
def test_safe_fact(fact, ok):
    assert safe_fact(fact) is ok


def test_tool_declarations_cover_the_app_tools():
    names = {t["name"] for t in VOICE_TOOLS}
    assert names == {"start_task", "start_guide", "open_screen", "read_updates", "check_link", "remember"}
    from google.genai import types

    for t in VOICE_TOOLS:
        types.FunctionDeclaration(**t)  # valid for the Live API config
