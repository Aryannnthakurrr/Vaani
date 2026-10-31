"""/agent/step, /intent, /classify/messages, /extract/appointment, /digest — the Gemini 'brain' routes."""

from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, Depends

from app.deps import llm_dep, require_device
from app.llm.base import LLMClient
from app.schemas import (
    GUIDE_ACTIONS,
    AgentStepRequest,
    Appointment,
    ClassifyRequest,
    ClassifyResponse,
    DigestRequest,
    DigestResponse,
    ExtractRequest,
    IntentRequest,
    IntentResponse,
    dump_action,
    parse_action,
)
from app.speech.language_style import classify_style

log = logging.getLogger("saathi.agent")
router = APIRouter(dependencies=[Depends(require_device)])


@router.post("/agent/step")
async def agent_step(req: AgentStepRequest, llm: LLMClient = Depends(llm_dep)) -> dict[str, Any]:
    started = time.perf_counter()
    raw = await llm.agent_step(req)
    try:
        action = parse_action(raw)
        if req.mode == "GUIDE" and action.action not in GUIDE_ACTIONS:
            # Defence in depth: the app enforces this too.
            raise ValueError("non-guide action in GUIDE mode")
        result = dump_action(action)
    except Exception as exc:
        log.warning("agent_step rejected model output: %s", type(exc).__name__)
        result = {
            "action": "fail",
            "message": "I couldn't work out the next step. Shall I show you instead?",
            "reason": "invalid action from model",
        }
    # Never log screen text; only sizes and the action type.
    log.info(
        "agent_step mode=%s screen_chars=%d history=%d action=%s latency_ms=%d",
        req.mode,
        len(req.screen_text),
        len(req.history),
        result["action"],
        (time.perf_counter() - started) * 1000,
    )
    return result


@router.post("/intent", response_model=IntentResponse)
async def intent(req: IntentRequest, llm: LLMClient = Depends(llm_dep)) -> IntentResponse:
    style = req.reply_style or classify_style(req.utterance)
    result = await llm.intent(req.utterance, style)
    log.info("intent=%s style=%s", result.intent, style)
    return result


@router.post("/classify/messages", response_model=ClassifyResponse)
async def classify_messages(req: ClassifyRequest, llm: LLMClient = Depends(llm_dep)) -> ClassifyResponse:
    results = await llm.classify(req.messages)
    log.info("classify count=%d", len(req.messages))  # bodies are never logged
    return ClassifyResponse(results=results)


@router.post("/extract/appointment", response_model=Appointment)
async def extract_appointment(req: ExtractRequest, llm: LLMClient = Depends(llm_dep)) -> Appointment:
    return await llm.extract_appointment(req.screen_text)


@router.post("/digest", response_model=DigestResponse)
async def digest(req: DigestRequest, llm: LLMClient = Depends(llm_dep)) -> DigestResponse:
    return DigestResponse(text=await llm.digest(req))
