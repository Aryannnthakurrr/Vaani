"""Skills: run outcomes from the phone, the skill list and cost stats, and family approval."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query

from app.config import Settings
from app.deps import llm_dep, require_device, settings_dep
from app.family.links import key_matches
from app.llm.base import LLMClient
from app.schemas import AgentOutcomeRequest
from app.skills import get_code_skills, get_engine
from app.skills.store import Skill

log = logging.getLogger("saathi.skills")
router = APIRouter()


def _public(s: Skill) -> dict[str, Any]:
    return {
        "id": s.id, "kind": s.kind, "name": s.name, "version": s.version, "status": s.status,
        "held_out_pass": s.held_out_pass, "shadow_matches": s.shadow_matches, "runs": s.runs,
        "recent": s.recent, "last_error": s.last_error, "approved_by": s.approved_by,
        "trained_on_runs": len(s.trained_on), "compile_tokens": s.tokens_spent,
    }  # fmt: skip


@router.post("/agent/outcome")
async def agent_outcome(req: AgentOutcomeRequest, _device: str = Depends(require_device)) -> dict[str, bool]:
    await get_engine().outcome(req.run_id, req.outcome)
    log.info("agent outcome=%s task=%s", req.outcome, req.task_hint or "-")
    return {"ok": True}


@router.get("/skills")
async def list_skills(_device: str = Depends(require_device)) -> dict[str, Any]:
    engine = get_engine()
    return {"skills": [_public(s) for s in engine.store.all()], "stats": engine.store.stats()}


@router.post("/skills/compile")
async def compile_now(
    _device: str = Depends(require_device), llm: LLMClient = Depends(llm_dep)
) -> dict[str, Any]:
    """Run the compiler now instead of waiting for the scheduler (demos, tests)."""
    made = await get_engine().compile_pending(llm)
    code = await get_code_skills().compile(llm)
    return {"compiled": made + ([code] if code else [])}


def _family_actor(device_id: str, key: str | None, settings: Settings) -> str:
    if not key_matches(settings, device_id, key):
        raise HTTPException(status_code=404, detail="not found")
    return f"family:{device_id[:8]}"


@router.post("/family/{device_id}/skills/{skill_id}/{decision}")
async def family_decide(
    device_id: str, skill_id: int, decision: str, key: str | None = Query(default=None),
    settings: Settings = Depends(settings_dep),
) -> dict[str, Any]:  # fmt: skip
    """Family approves or rejects a tested skill from the dashboard (the elder is never asked to judge code)."""
    actor = _family_actor(device_id, key, settings)
    engine = get_engine()
    if decision == "approve":
        skill = engine.approve(skill_id, actor)
    elif decision == "reject":
        skill = engine.reject(skill_id, actor)
    else:
        raise HTTPException(status_code=400, detail="decision must be approve or reject")
    if skill is None:
        raise HTTPException(status_code=409, detail="skill is not waiting for that decision")
    return _public(skill)
