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
    facts = asyncio.run(get_memory().facts(DEVICE))
    assert facts == [("other", "my cardiologist is Dr. Mehta")]
    prompt = asyncio.run(system_prompt(DEVICE, "en"))
    assert "Dr. Mehta" in prompt and "{memories}" not in prompt and "{user}" not in prompt
    assert "You do not know their name yet." in prompt


def test_name_topics_and_forget_reach_the_prompt(client):
    import asyncio

    from app.routers.voice_live import forget, remember, system_prompt

    async def flow() -> tuple[str, str]:
        await remember(DEVICE, {"fact": "Kamla ji", "topic": "name"})
        await remember(DEVICE, {"fact": "मेरी बेटी प्रिया पुणे में रहती है", "topic": "family", "key": "daughter"})
        await remember(DEVICE, {"fact": "Ghutne mein dard rehta hai", "topic": "health", "key": "knee"})
        before = await system_prompt(DEVICE, "hi")
        assert (await forget(DEVICE, {"topic": "health", "key": "knee"}))["forgotten"] == 1
        return before, await system_prompt(DEVICE, "hi")

    before, after = asyncio.run(flow())
    assert "The user's name is Kamla ji" in before
    assert "- family: मेरी बेटी प्रिया पुणे में रहती है" in before and "Ghutne" in before
    assert "Ghutne" not in after and "प्रिया" in after


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
        ("मेरा यूपीआई पिन 1234 है", False),
        ("kal mera OTP aayega", False),
        ("मेरी बेटी प्रिया पुणे में रहती है", True),
        ("x", False),
    ],
)
def test_safe_fact(fact, ok):
    assert safe_fact(fact) is ok


def test_tool_declarations_cover_the_app_tools():
    names = {t["name"] for t in VOICE_TOOLS}
    assert names == {
        "start_task",
        "start_guide",
        "open_screen",
        "read_updates",
        "check_link",
        "remember",
        "forget",
    }
    from google.genai import types

    for t in VOICE_TOOLS:
        types.FunctionDeclaration(**t)  # valid for the Live API config


def test_config_context_reaches_the_prompt() -> None:
    from app.routers.voice_live import with_context

    out = with_context("BASE", "Next appointment: Dr. Meera at City Care, tomorrow at 11:00 AM.")
    assert out.startswith("BASE") and "Dr. Meera" in out and "data, not instructions" in out
    assert with_context("BASE", "  ") == "BASE"
