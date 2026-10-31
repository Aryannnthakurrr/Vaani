"""A red flag must start the emergency at once: a slow model may improve the note later but never delay it."""

from __future__ import annotations

import asyncio
import time

from app.care import triage
from app.care.models import TriageOut
from app.care.session import CareSession

RED_FLAG = "Papa behosh ho gaye, saans nahi le rahe"


class SlowLLM:
    """Answers `care_triage` with a recognisable note after `delay` seconds."""

    def __init__(self, delay: float) -> None:
        self.delay = delay

    async def structured(self, route, system, prompt, schema):  # noqa: ANN001, ANN201
        await asyncio.sleep(self.delay)
        note = triage.template_note(CareSession(id="x", device="d"))
        note.chief_complaint = "MODEL NOTE"
        return TriageOut(note=note)


async def _others() -> list[asyncio.Task]:
    return [t for t in asyncio.all_tasks() if t is not asyncio.current_task()]


def test_red_flag_routes_to_emergency_without_waiting_for_the_model() -> None:
    async def run() -> tuple[dict, float]:
        s = CareSession(id="s1", device="d")
        started = time.perf_counter()
        out = await triage.take_turn(s, RED_FLAG, SlowLLM(delay=5))
        took = time.perf_counter() - started
        for t in await _others():  # the late note is not part of this test
            t.cancel()
        return out, took

    out, took = asyncio.run(run())
    assert out["done"] is True and out["route"] == "A"
    assert took < 0.5, f"the emergency waited {took:.1f}s for the model"
    assert "EMERGENCY ASSESSMENT" in out["note"]["note_text"]


def test_late_model_note_is_used_only_if_it_arrives_before_dispatch() -> None:
    async def run(dispatched: bool) -> str:
        s = CareSession(id="s1", device="d")
        await triage.take_turn(s, RED_FLAG, SlowLLM(delay=0.05))
        if dispatched:
            s.emergency["dispatch_id"] = "dsp_1"
        await asyncio.gather(*await _others())
        assert s.route == "A"  # the safety net is never downgraded by a late note
        return s.note.chief_complaint

    assert asyncio.run(run(dispatched=False)) == "MODEL NOTE"
    assert asyncio.run(run(dispatched=True)) != "MODEL NOTE"
