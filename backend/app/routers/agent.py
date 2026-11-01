"""/agent/step, /intent, /classify/messages, /extract/appointment, /digest — the Gemini 'brain' routes."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Coroutine
from typing import Any

from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel

from app.abilities import get_abilities, user_key
from app.care import remembered
from app.deps import llm_dep, require_device
from app.llm.base import LLMClient
from app.llm.prompts import render
from app.memory import AGENTS, TOPICS, facts_for, get_memory
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
    # so a routine can be compiled from it later (docs/features/LEARNING.md).
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


class _AbilityReply(BaseModel):
    reply_text: str


async def ability_reply(
    llm: LLMClient, profile: str, utterance: str, done: dict[str, Any], style: str
) -> str:
    """The LLM's spoken answer around a compiled ability's result; the code's own sentence if the LLM fails."""
    persona = get_abilities().persona(profile)
    facts = await facts_for(user_key(profile), "voice")
    lines = [text for _, text in facts[:12]] + (persona.quirks if persona else [])
    about = "\n".join(f"- {line}" for line in lines)
    system = render(
        "ability_reply", person=persona.name if persona else "the user", about=about, reply_style=style
    )
    prompt = json.dumps({"they_said": utterance, "helper": done["ability"], "helper_result": done["say"]},
                        ensure_ascii=False)  # fmt: skip
    out = await llm.structured("ability_reply", system, prompt, _AbilityReply)
    text = out.reply_text.strip() if isinstance(out, _AbilityReply) else ""
    return text or done["say"]


@router.post("/intent", response_model=IntentResponse)
async def intent(
    req: IntentRequest,
    llm: LLMClient = Depends(llm_dep),
    x_saathi_profile: str | None = Header(default=None),
) -> IntentResponse:
    style = req.reply_style or classify_style(req.utterance)
    started = time.perf_counter()
    # One of the user's own compiled abilities may do the bookkeeping (store a reading, add up, count down); the LLM
    # still does the talking, with that result. An urgent result is said as the code wrote it: safety advice never
    # waits on a model.
    abilities = get_abilities()
    if (ability := abilities.match(x_saathi_profile, req.utterance)) is not None:
        done = await abilities.run(ability.user, ability, req.utterance)
        if done is not None:
            persona = abilities.persona(ability.user)
            spoken = persona.language if persona and persona.language in {"en", "hi", "hinglish"} else style
            reply = (
                done["say"]
                if done["urgent"]
                else await ability_reply(llm, ability.user, req.utterance, done, spoken)
            )
            log.info(
                "intent=ability name=%s latency_ms=%d", ability.name, (time.perf_counter() - started) * 1000
            )
            return IntentResponse(intent="ability", reply_style=spoken, reply_text=reply)  # type: ignore[arg-type]

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


def _owner(device: str, profile: str | None) -> str:
    persona = get_abilities().persona(profile)
    return user_key(persona.id) if persona else device


@router.get("/memory/appointment")
async def stored_appointment(
    device: str = Depends(require_device), x_saathi_profile: str | None = Header(default=None)
) -> dict[str, Any]:
    """The next appointment the doctor feature booked (from Meko-backed memory), for "cab to my doctor's appointment"."""
    return {"appointment": await remembered.next_appointment(_owner(device, x_saathi_profile))}


@router.get("/memory/learned")
async def learned(
    device: str = Depends(require_device), x_saathi_profile: str | None = Header(default=None)
) -> dict[str, Any]:
    """Everything Vaani remembers about this user, each with the agent that learnt it and where."""
    memory = get_memory()
    entries = await memory.entries(_owner(device, x_saathi_profile)) if hasattr(memory, "entries") else []
    return {
        "agents": AGENTS,
        "facts": [{"topic": e["topic"], "text": e["text"], "agent": e["meta"].get("agent", "saathi-voice"),
                   "learned_from": e["meta"].get("learned_from", "")} for e in entries],
    }  # fmt: skip


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
