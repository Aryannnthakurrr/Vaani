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

from app.abilities import food, get_abilities, user_key
from app.config import Settings, get_settings
from app.deps import device_ok
from app.llm.prompts import render
from app.memory import get_memory
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
            "sugar_g_per_100": {
                "type": "number",
                "description": "Total sugars per 100 g or ml, from the nutrition panel",
            },
            "sodium_mg_per_100": {
                "type": "number",
                "description": "Sodium in mg per 100 g or ml, from the nutrition panel",
            },
            "per": {
                "type": "string",
                "enum": ["g", "ml"],
                "description": "Whether the panel is per 100 g or 100 ml",
            },
        },
        "required": ["expiry_status", "confidence"],
    },
}


def mock_card(today: date | None = None, conditions: set[str] | None = None) -> ProductCard:
    """The scripted pack: a Frooti for a diabetic shopper and Lay's for high BP show each warning offline."""
    today = today or date.today()
    conditions = conditions or set()
    if "diabetes" in conditions:
        return ProductCard(name="Frooti Mango Drink", mrp=20, quantity="200 ml",
                           expiry_iso=(today + timedelta(days=120)).isoformat(), expiry_status="ok", confidence=0.9)  # fmt: skip
    if "bp" in conditions:
        return ProductCard(name="Lay's Classic Salted", mrp=20, quantity="52 g",
                           expiry_iso=(today + timedelta(days=60)).isoformat(), expiry_status="ok", confidence=0.9)  # fmt: skip
    return ProductCard(
        name="Parle-G Gold Biscuits",
        mrp=140,
        quantity="1 kg",
        expiry_iso=(today + timedelta(days=5)).isoformat(),
        expiry_status="soon",
        confidence=0.92,
    )


def with_health(card: ProductCard, conditions: set[str], style: str) -> ProductCard:
    """The card with the fixed-code sugar or salt warning for this shopper, if any."""
    found = food.check(card.model_dump(), conditions, style)
    if found is None:
        return card.model_copy(update={"health_warning": None, "health_level": None})
    return card.model_copy(update={"health_level": found[0], "health_warning": found[1]})


_MOCK_LINES = {
    "en": "This is Parle-G Gold, 1 kilo. The printed MRP is ₹140. It expires in 5 days, so please use it soon.",
    "hi": "यह पारले-जी गोल्ड है, 1 किलो। छपा हुआ एमआरपी ₹140 है। यह 5 दिन में एक्सपायर होगा, जल्दी इस्तेमाल कीजिए।",
    "hinglish": "Yeh Parle-G Gold hai, 1 kilo. Printed MRP ₹140 hai. Yeh 5 din mein expire hoga, jaldi use kijiye.",
}


async def _mock_session(ws: WebSocket, style: str, conditions: set[str] | None = None) -> None:
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
                card = with_health(mock_card(conditions=conditions), conditions or set(), style)
                await ws.send_json({"type": "product_card", "card": card.model_dump()})
                line = (
                    _MOCK_LINES.get(style, _MOCK_LINES["en"])
                    if (card.name or "").startswith("Parle")
                    else f"{card.name}."
                )
                await ws.send_json(
                    {"type": "text", "text": " ".join(x for x in (line, card.health_warning) if x)}
                )
                await ws.send_json({"type": "turn_complete"})
        elif kind == "end":
            return


async def _gemini_session(
    ws: WebSocket, settings: Settings, style: str, context: str = "", conditions: set[str] | None = None
) -> None:
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
    warned: set[str] = set()  # products already warned about this session: say it once
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
                    await _forward(ws, session, resp, types, conditions or set(), style, warned)

        tasks = [asyncio.create_task(upstream()), asyncio.create_task(downstream())]
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        finally:
            for task in tasks:
                task.cancel()


async def _forward(
    ws: WebSocket, session: Any, resp: Any, types: Any, conditions: set[str], style: str, warned: set[str]
) -> None:
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
            answer: dict[str, Any] = {"ok": True}
            if call.name == "update_product_card":
                card = with_health(ProductCard.model_validate(call.args or {}), conditions, style)
                await ws.send_json({"type": "product_card", "card": card.model_dump()})
                product = (card.name or "").lower()
                if card.health_warning and product not in warned:
                    warned.add(product)
                    # Facts for the model's own gentle nudge (camera.md), not a line to read out.
                    answer["health_check"] = {"level": card.health_level, "finding": card.health_warning}
            responses.append(types.FunctionResponse(id=call.id, name=call.name, response=answer))
        await session.send_tool_response(function_responses=responses)


async def shopper_conditions(context: str, profile: str | None) -> set[str]:
    """Diabetes and BP from the app's context line, the demo persona and the user's remembered health facts."""
    texts = [context]
    persona = get_abilities().persona(profile)
    if persona is not None:
        texts += persona.conditions
        with contextlib.suppress(Exception):
            texts += [t for _, t in await get_memory().facts(user_key(persona.id), ["health"])]
    return food.conditions_from(*texts)


@router.websocket("/live/camera")
async def live_camera(ws: WebSocket) -> None:
    device = ws.query_params.get("device", "")
    if not device_ok(
        device, ws.headers.get("x-saathi-key")
    ):  # the key rides in a header, never the logged URL
        await ws.close(code=4401)
        return
    await ws.accept()
    settings = get_settings()
    style = ws.query_params.get("reply_style", "en")
    context = ""
    profile = ws.headers.get("x-saathi-profile")
    try:
        # The mock reads it too, so its scripted pack follows the shopper's health.
        # The app's first message is its config: reply style plus what Saathi knows about the user (health,
        # next appointment) for gentle shopping advice. Sent in the socket, never the URL, so it isn't logged.
        with contextlib.suppress(TimeoutError, ValueError):
            first = json.loads(await asyncio.wait_for(ws.receive_text(), CONFIG_WAIT_S))
            if first.get("type") == "config":
                style = first.get("reply_style", style)
                context = str(first.get("context") or "")[:500]
        conditions = await shopper_conditions(context, profile)
        if settings.mock:
            await _mock_session(ws, style, conditions)
        else:
            await _gemini_session(ws, settings, style, context, conditions)
    except (WebSocketDisconnect, TimeoutError):
        log.info("camera session ended")
    except Exception as exc:
        log.warning("camera session error: %s", type(exc).__name__)
        with contextlib.suppress(Exception):
            await ws.send_json({"type": "error", "message": "The camera helper stopped. Please try again."})
    finally:
        with contextlib.suppress(Exception):
            await ws.close()
