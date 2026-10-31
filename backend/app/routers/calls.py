"""POST /calls/clinic/turn — one turn of the simulated clinic call (stretch, SPEC §7.7)."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends

from app.deps import llm_dep, require_device
from app.llm.base import LLMClient
from app.llm.clinic_call import ClinicCallRequest, ClinicCallTurn

log = logging.getLogger("saathi.calls")
router = APIRouter(dependencies=[Depends(require_device)])


@router.post("/calls/clinic/turn", response_model=ClinicCallTurn)
async def clinic_turn(req: ClinicCallRequest, llm: LLMClient = Depends(llm_dep)) -> ClinicCallTurn:
    turn = await llm.clinic_call_turn(req)
    # Never log what was said; only the shape of the call.
    log.info("clinic call turn=%d done=%s", len(req.transcript), turn.done)
    return turn
