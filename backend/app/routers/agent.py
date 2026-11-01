"""/agent/step, /intent, /classify/messages, /extract/appointment, /digest — the Gemini 'brain' routes."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Coroutine
from typing import Any

from fastapi import APIRouter, Depends, Header

from app.abilities import get_abilities
from app.deps import llm_dep, require_device
from app.llm.base import LLMClient
from app.memory import TOPICS, get_memory
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
from app.skills import get_code_skills, get_engine
from app.speech.language_style import classify_style

log = logging.getLogger("saathi.agent")
router = APIRouter(dependencies=[Depends(require_device)])


@router.post("/agent/step")
async def agent_step(
    req: AgentStepRequest, llm: LLMClient = Depends(llm_dep), device: str = Depends(require_device)
) -> dict[str, Any]:
    started = time.perf_counter()

    async def ask_llm() -> dict[str, Any]:
        return _checked(req, await llm.agent_step(req))

    # A compiled routine answers when one is active for this task; otherwise the LLM does, and the run is recorded
    # so a routine can be compiled from it later (docs/LEARNING.md).
    served = await get_engine().step(
        device=device, run_id=req.run_id, task=req.task_hint, goal=req.goal, mode=req.mode,
        screen_text=req.screen_text, llm_step=ask_llm,
    )  # fmt: skip
    result = _checked(req, served.action)
    if req.run_id:
        # Agent state checkpoint (Meko conversation per run). Off the request path; never the screen text itself.
        step = {"task": req.task_hint or "", "mode": req.mode, "screen_chars": len(req.screen_text),
                "package": req.screen_text.split("\n", 1)[0][:80] if req.screen_text.startswith("package=") else "",
                "action": {k: v for k, v in result.items() if k not in {"text", "message"}},
                "reason": result.get("reason", ""), "handler": served.handler, "seq": len(req.history)}  # fmt: skip
        _background(get_memory().log_step(device, req.run_id, req.goal, step))
    # Never log screen text; only sizes and the action type.
    log.info(
        "agent_step mode=%s screen_chars=%d history=%d action=%s handler=%s latency_ms=%d",
        req.mode,
        len(req.screen_text),
        len(req.history),
        result["action"],
        served.handler,
        (time.perf_counter() - started) * 1000,
    )
    return result


_tasks: set[asyncio.Task[Any]] = set()


def _background(coro: Coroutine[Any, Any, Any]) -> None:
    async def guarded() -> None:
        try:
            await coro
        except Exception as exc:  # noqa: BLE001 - memory is best effort
            log.info("memory checkpoint skipped: %s", type(exc).__name__)

    task = asyncio.create_task(guarded())
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


def _checked(req: AgentStepRequest, raw: dict[str, Any]) -> dict[str, Any]:
    """Validate any action (model- or skill-produced) against the shared schema and GUIDE restrictions."""
    try:
        action = parse_action(raw)
        if req.mode == "GUIDE" and action.action not in GUIDE_ACTIONS:
            # Defence in depth: the app enforces this too.
            raise ValueError("non-guide action in GUIDE mode")
        return dump_action(action)
    except Exception as exc:
        log.warning("agent_step rejected action: %s", type(exc).__name__)
        return {
            "action": "fail",
            "message": "I couldn't work out the next step. Shall I show you instead?",
            "reason": "invalid action",
        }


@router.post("/intent", response_model=IntentResponse)
async def intent(
    req: IntentRequest,
    llm: LLMClient = Depends(llm_dep),
    x_saathi_profile: str | None = Header(default=None),
) -> IntentResponse:
    style = req.reply_style or classify_style(req.utterance)
    started = time.perf_counter()
    # The user's own compiled abilities come first: work the LLM used to do for them, now done by tested code.
    abilities = get_abilities()
    if (ability := abilities.match(x_saathi_profile, req.utterance)) is not None:
        done = await abilities.run(ability.user, ability, req.utterance)
        if done is not None:
            log.info("intent=ability name=%s latency_ms=%d", ability.name, (time.perf_counter() - started) * 1000)
            persona = abilities.persona(ability.user)
            spoken = persona.language if persona and persona.language in {"en", "hi", "hinglish"} else style
            return IntentResponse(intent="ability", reply_style=spoken, reply_text=done["say"])  # type: ignore[arg-type]

    async def ask_llm() -> IntentResponse:
        return await llm.intent(req.utterance, style)

    # A code skill written by the strong model answers when it is sure; otherwise the LLM does (and teaches it).
    result, handler = await get_code_skills().intent(req.utterance, style, ask_llm)
    log.info("intent=%s style=%s handler=%s latency_ms=%d", result.intent, style, handler,
             (time.perf_counter() - started) * 1000)  # fmt: skip
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


@router.delete("/memory")
async def forget_everything(device: str = Depends(require_device)) -> dict[str, int]:
    """ "Forget everything": every remembered fact on every topic, and the saved screen-task steps."""
    memory = get_memory()
    facts = 0
    for topic in TOPICS:
        facts += int(await memory.forget(device, topic) or 0)
    local = getattr(memory, "local", memory)
    steps = await local.forget_steps(device) if hasattr(local, "forget_steps") else 0
    return {"facts": facts, "steps": steps}
