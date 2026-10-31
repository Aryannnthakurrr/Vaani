"""WS /live/camera — relays camera frames and mic audio to Gemini Live (or a scripted mock)."""

from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import logging
from datetime import date, timedelta
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.config import Settings, get_settings
from app.llm.prompts import render
from app.schemas import ProductCard

log = logging.getLogger("saathi.live")
router = APIRouter()
CONFIG_WAIT_S = 2.0
INACTIVITY_S = 180
MOCK_FRAMES_BEFORE_CARD = 2

UPDATE_CARD_TOOL = {
    "name": "update_product_card",
    "description": "Update the product card shown on the user's phone with everything known so far.",
    "parameters_json_schema": {
        "type": "object",
        "properties": {
            "name": {"type": "string", "description": "Brand, product, variant and pack size"},
            "mrp": {"type": "number", "description": "Printed MRP in rupees"},
            "quantity": {"type": "string", "description": "Net quantity, e.g. '1 kg'"},
            "expiry_iso": {"type": "string", "description": "Expiry date YYYY-MM-DD"},
            "expiry_status": {"type": "string", "enum": ["ok", "soon", "expired", "unknown"]},
            "confidence": {"type": "number", "description": "0 to 1"},
        },
        "required": ["expiry_status", "confidence"],
    },
}


def mock_card(today: date | None = None) -> ProductCard:
    today = today or date.today()
    return ProductCard(
        name="Parle-G Gold Biscuits",
        mrp=140,
        quantity="1 kg",
        expiry_iso=(today + timedelta(days=5)).isoformat(),
        expiry_status="soon",
        confidence=0.92,
    )


_MOCK_LINES = {
    "en": "This is Parle-G Gold, 1 kilo. The printed MRP is ₹140. It expires in 5 days, so please use it soon.",
    "hi": "यह पारले-जी गोल्ड है, 1 किलो। छपा हुआ एमआरपी ₹140 है। यह 5 दिन में एक्सपायर होगा, जल्दी इस्तेमाल कीजिए।",
    "hinglish": "Yeh Parle-G Gold hai, 1 kilo. Printed MRP ₹140 hai. Yeh 5 din mein expire hoga, jaldi use kijiye.",
}


async def _mock_session(ws: WebSocket, style: str) -> None:
    frames = 0
    sent = False
    while True:
        msg = json.loads(await asyncio.wait_for(ws.receive_text(), INACTIVITY_S))
        kind = msg.get("type")
        if kind == "config":
            style = msg.get("reply_style", style)
        elif kind == "frame":
            frames += 1
            if frames == 1:
                await ws.send_json(
                    {
                        "type": "text",
                        "text": {
                            "en": "I can see a packet. Hold it steady.",
                            "hi": "मुझे एक पैकेट दिख रहा है। इसे स्थिर रखिए।",
                            "hinglish": "Mujhe ek packet dikh raha hai. Ise steady rakhiye.",
                        }.get(style, "I can see a packet. Hold it steady."),
                    }
                )
                await ws.send_json({"type": "turn_complete"})
            if frames >= MOCK_FRAMES_BEFORE_CARD and not sent:
                sent = True
                await ws.send_json({"type": "product_card", "card": mock_card().model_dump()})
                await ws.send_json({"type": "text", "text": _MOCK_LINES.get(style, _MOCK_LINES["en"])})
                await ws.send_json({"type": "turn_complete"})
        elif kind == "end":
            return


async def _gemini_session(ws: WebSocket, settings: Settings, style: str, context: str = "") -> None:
    from google import genai
    from google.genai import types

    client = genai.Client(api_key=settings.gemini_api_key)
    config = types.LiveConnectConfig(
        response_modalities=["AUDIO"],
        system_instruction=render(
            "camera",
            reply_style=style,
            user=context.strip() or "Nothing is known about the shopper's health.",
        ),
        tools=[types.Tool(function_declarations=[types.FunctionDeclaration(**UPDATE_CARD_TOOL)])],
        output_audio_transcription=types.AudioTranscriptionConfig(),
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                    voice_name=settings.route("camera_live").get("voice", "Sulafat")
                )
            )
        ),
    )
    model = settings.route("camera_live")["model"]
    async with client.aio.live.connect(model=model, config=config) as session:

        async def upstream() -> None:
            while True:
                msg = json.loads(await asyncio.wait_for(ws.receive_text(), INACTIVITY_S))
                kind = msg.get("type")
                if kind == "frame":
                    data = base64.b64decode(msg["jpeg_b64"])
                    await session.send_realtime_input(video=types.Blob(data=data, mime_type="image/jpeg"))
                elif kind == "audio":
                    data = base64.b64decode(msg["pcm_b64"])
                    await session.send_realtime_input(
                        audio=types.Blob(data=data, mime_type="audio/pcm;rate=16000")
                    )
                elif kind == "end":
                    return

        async def downstream() -> None:
            while True:
                async for resp in session.receive():
                    await _forward(ws, session, resp, types)

        tasks = [asyncio.create_task(upstream()), asyncio.create_task(downstream())]
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        finally:
            for task in tasks:
                task.cancel()


async def _forward(ws: WebSocket, session: Any, resp: Any, types: Any) -> None:
    if resp.data:
        await ws.send_json(
            {"type": "audio", "pcm_b64": base64.b64encode(resp.data).decode(), "sample_rate": 24000}
        )
    content = getattr(resp, "server_content", None)
    transcript = getattr(getattr(content, "output_transcription", None), "text", None)
    if transcript:
        await ws.send_json({"type": "text", "text": transcript})  # a fragment of the current turn
    # The phone uses these to drop speech the user talked over and to start a fresh caption per reply.
    if getattr(content, "interrupted", False):
        await ws.send_json({"type": "interrupted"})
    if getattr(content, "turn_complete", False):
        await ws.send_json({"type": "turn_complete"})
    tool_call = getattr(resp, "tool_call", None)
    if tool_call:
        responses = []
        for call in tool_call.function_calls or []:
            if call.name == "update_product_card":
                card = ProductCard.model_validate(call.args or {})
                await ws.send_json({"type": "product_card", "card": card.model_dump()})
            responses.append(types.FunctionResponse(id=call.id, name=call.name, response={"ok": True}))
        await session.send_tool_response(function_responses=responses)


@router.websocket("/live/camera")
async def live_camera(ws: WebSocket) -> None:
    device = ws.query_params.get("device", "")
    if len(device) < 8:
        await ws.close(code=4401)
        return
    await ws.accept()
    settings = get_settings()
    style = ws.query_params.get("reply_style", "en")
    context = ""
    try:
        if not settings.mock:
            # The app's first message is its config: reply style plus what Saathi knows about the user (health,
            # next appointment) for gentle shopping advice. Sent in the socket, never the URL, so it isn't logged.
            with contextlib.suppress(TimeoutError, ValueError):
                first = json.loads(await asyncio.wait_for(ws.receive_text(), CONFIG_WAIT_S))
                if first.get("type") == "config":
                    style = first.get("reply_style", style)
                    context = str(first.get("context") or "")[:500]
        if settings.mock:
            await _mock_session(ws, style)
        else:
            await _gemini_session(ws, settings, style, context)
    except (WebSocketDisconnect, TimeoutError):
        log.info("camera session ended")
    except Exception as exc:
        log.warning("camera session error: %s", type(exc).__name__)
        with contextlib.suppress(Exception):
            await ws.send_json({"type": "error", "message": "The camera helper stopped. Please try again."})
    finally:
        with contextlib.suppress(Exception):
            await ws.close()
