"""Personal compiled abilities of the demo users: list them, run one on typed text, reset to the seeded state."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.abilities import get_abilities
from app.deps import require_device

router = APIRouter()


class AbilityRun(BaseModel):
    text: str = Field(min_length=1, max_length=500)


@router.get("/abilities")
async def personas() -> dict[str, Any]:
    abilities = get_abilities()
    return {"personas": [abilities.listing(p) for p in abilities.personas]}


@router.get("/abilities/{profile}")
async def persona(profile: str) -> dict[str, Any]:
    listing = get_abilities().listing(profile)
    if listing is None:
        raise HTTPException(status_code=404, detail="no such demo user")
    return listing


@router.post("/abilities/{profile}/run")
async def run(profile: str, req: AbilityRun, _device: str = Depends(require_device)) -> dict[str, Any]:
    """What the voice path does, for a typed request: the matching active ability, or 404 (the LLM would answer)."""
    abilities = get_abilities()
    ability = abilities.match(profile, req.text)
    if ability is None:
        raise HTTPException(status_code=404, detail="no ability for that; the LLM answers it")
    done = await abilities.run(profile, ability, req.text)
    if done is None:
        raise HTTPException(status_code=503, detail="the ability failed its checks; the LLM answers it")
    return done


@router.post("/abilities/{profile}/reset")
async def reset(profile: str, _device: str = Depends(require_device)) -> dict[str, bool]:
    if get_abilities().persona(profile) is None:
        raise HTTPException(status_code=404, detail="no such demo user")
    await get_abilities().reset(profile)
    return {"ok": True}
