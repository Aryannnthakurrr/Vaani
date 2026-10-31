"""The learning loop: routine format and safety, compiler replay tests, and the full lifecycle
(LLM runs -> compile -> approve -> shadow -> active -> degrade)."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from app.skills.compiler import RunTrace, compile_routine, heuristic_routine, replay, split
from app.skills.engine import Config, SkillEngine
from app.skills.routine import Routine, parse_screen, propose, same_action, validate
from app.skills.store import SkillStore
from tests.conftest import load_screens, serialize_screen

UBER = [serialize_screen(s) for s in load_screens("uber_book")]


def _id(screen: str, text: str) -> int:
    return next(n.id for n in parse_screen(screen) if text.lower() in n.label.lower())


def uber_run(run_id: str, dest: str = "Apollo Hospital") -> RunTrace:
    """What the LLM did on the recorded Uber screens."""
    return RunTrace(run_id, f"Book the cheapest non-shared Uber ride to {dest}.", [
        {"screen": UBER[0], "action": {"action": "tap", "id": _id(UBER[0], "Where to?")}},
        {"screen": UBER[1], "action": {"action": "type", "id": 3, "text": dest}},
        {"screen": UBER[2], "action": {"action": "tap", "id": _id(UBER[2], "Sarita Vihar")}},
        {"screen": UBER[3], "action": {"action": "confirm", "summary": "Uber Go, ₹184. Book it?",
                                        "then": {"action": "tap", "id": _id(UBER[3], "Confirm Uber Go")}}},
        {"screen": UBER[4], "action": {"action": "done", "message": "Your cab is booked."}},
    ])  # fmt: skip


GENERAL = {
    "task": "uber_book",
    "slots": {"destination": r"ride to (.+?)\.?$"},
    "steps": [
        {"expect": ["Where to?"], "action": "tap", "target": {"text": "Where to?"}},
        {"expect": ["Pickup location"], "action": "type", "target": {"hint": "Where to?"}, "text": "{destination}"},
        {"expect": ["{destination}"], "action": "tap", "target": {"text_contains": "{destination},", "index": 0}},
        {"expect": ["Choose a ride"], "action": "confirm", "summary": "{ride}. Book it?",
         "read": {"ride": {"text_regex": "^(Uber Go\\s+₹\\s?\\d+)"}},
         "then": {"action": "tap", "target": {"text_regex": "^Confirm"}}},
        {"expect": ["arriving"], "action": "done", "message": "{line}",
         "read": {"line": {"text_contains": "arriving"}}},
    ],
}  # fmt: skip


# --------------------------------------------------------------------------- routine format


def test_parse_screen_reads_text_hint_and_flags() -> None:
    nodes = parse_screen(UBER[1])
    where = next(n for n in nodes if n.hint == "Where to?")
    assert "editable" in where.flags and where.id == 3


def test_general_routine_replays_and_fills_slots() -> None:
    routine = Routine.model_validate(GENERAL)
    assert validate(routine) == []
    ok, why = replay(routine, uber_run("r1"))
    assert ok, why
    p = propose(routine, UBER[1], "Book the cheapest non-shared Uber ride to Apollo Hospital.", 1)
    assert p and p.action == {"action": "type", "id": 3, "text": "Apollo Hospital"}
    done = propose(routine, UBER[4], "Book the cheapest non-shared Uber ride to Apollo Hospital.", 4)
    assert done and "arriving" in done.action["message"]


def test_safety_rejects_bare_commit_taps_and_forbidden_actions() -> None:
    bad = json.loads(json.dumps(GENERAL))
    bad["steps"][3] = {"expect": ["Choose a ride"], "action": "tap", "target": {"text": "Confirm Uber Go"}}
    assert any("must be wrapped in confirm" in p for p in validate(Routine.model_validate(bad)))
    with pytest.raises(ValueError):
        Routine.model_validate({"task": "x", "steps": [{"action": "open_deeplink"}]})
    typed = json.loads(json.dumps(GENERAL))
    typed["steps"][1]["text"] = "*401*9876543210#"
    assert any("dial codes" in p for p in validate(Routine.model_validate(typed)))


def test_routine_never_uses_stale_ids() -> None:
    """Targets resolve by label, so a reordered screen still gets the right element."""
    routine = Routine.model_validate(GENERAL)
    shuffled = "\n".join([UBER[0].splitlines()[0], '  [9] TextView "Suggestions" bounds=(0,0,1,1)',
                          '  [7] Button "Where to?" clickable bounds=(40,820,1040,940)'])  # fmt: skip
    p = propose(routine, shuffled, "ride to X.", 0)
    assert p and p.action == {"action": "tap", "id": 7}


def test_same_action_compares_targets() -> None:
    assert same_action({"action": "tap", "id": 1}, {"action": "tap", "id": 1, "reason": "x"})
    assert not same_action({"action": "tap", "id": 1}, {"action": "tap", "id": 2})
    assert same_action(
        {"action": "confirm", "then": {"id": 6}}, {"action": "confirm", "then": {"id": 6}, "summary": "s"}
    )


# --------------------------------------------------------------------------- compiler


def test_heuristic_compiler_builds_a_safe_literal_routine() -> None:
    routine = heuristic_routine("uber_book", uber_run("r1"))
    assert routine is not None and validate(routine) == []
    assert replay(routine, uber_run("r2"))[0]


def test_split_is_deterministic_and_holds_out() -> None:
    runs = [uber_run(f"r{i}") for i in range(5)]
    vis, held = split(runs, "uber_book@1")
    assert len(held) == 2 and len(vis) == 3
    assert [r.run_id for r in split(runs, "uber_book@1")[1]] == [r.run_id for r in held]


class FakeCompilerLLM:
    def __init__(self, outputs: list[str]) -> None:
        self.outputs = outputs
        self.prompts: list[str] = []

    async def compile_json(self, route: str, system: str, user: str) -> tuple[str, int]:
        self.prompts.append(user)
        return self.outputs.pop(0), 100


def test_compiler_feeds_failures_back_then_succeeds() -> None:
    broken = json.dumps({**GENERAL, "steps": GENERAL["steps"][:2]})  # stops early -> replay fails
    llm = FakeCompilerLLM([broken, json.dumps(GENERAL)])
    runs = [uber_run("a", "Apollo Hospital"), uber_run("b"), uber_run("c")]
    res = asyncio.run(compile_routine("uber_book", runs, llm, seed="s"))
    assert res.routine is not None and res.visible_ok and res.held_out_pass == 1.0
    assert "FAILED THESE CHECKS" in llm.prompts[1]
    assert res.tokens == 200


# --------------------------------------------------------------------------- full lifecycle


def _engine(tmp_path: Path, **cfg: Any) -> SkillEngine:
    return SkillEngine(SkillStore(tmp_path / "s.db"), Config(canary_every=0, **cfg))


async def _drive(
    engine: SkillEngine, run: RunTrace, llm_calls: list[int], device: str = "dev-12345678"
) -> list[str]:
    """Play a run through the engine; the 'LLM' answers with the recorded action."""
    handlers = []
    for step in run.steps:

        async def llm_step(a: dict[str, Any] = step["action"]) -> dict[str, Any]:
            llm_calls.append(1)
            return dict(a)

        served = await engine.step(device=device, run_id=run.run_id, task="uber_book", goal=run.goal, mode="ACT",
                                   screen_text=step["screen"], llm_step=llm_step)  # fmt: skip
        handlers.append(served.handler)
    await engine.outcome(run.run_id, "done")
    return handlers


def test_lifecycle_learns_then_serves_without_the_llm(tmp_path: Path) -> None:
    async def scenario() -> None:
        engine = _engine(tmp_path, min_runs=3, shadow_agree=2)
        calls: list[int] = []
        for i in range(3):  # 1. the LLM does the task three times
            await _drive(engine, uber_run(f"llm{i}"), calls)
        assert len(calls) == 15 and engine.due() == ["uber_book"]
        sid = await engine.compile_task("uber_book", FakeCompilerLLM([json.dumps(GENERAL)] * 2))  # 2. compile
        skill = engine.store.get(sid)
        assert skill and skill.status == "tested" and skill.verify()
        assert engine.approve(sid, "family:test") is not None  # 3. family approves
        for i in range(2):  # 4. shadow: LLM still answers, routine agrees every step
            await _drive(engine, uber_run(f"sh{i}"), calls)
        assert engine.store.get(sid).status == "active"  # type: ignore[union-attr]
        before = len(calls)
        handlers = await _drive(engine, uber_run("live", "Apollo Hospital"), calls)  # 5. active
        assert len(calls) == before, "an active routine must not call the LLM"
        assert all(h.startswith("skill:") for h in handlers)
        assert engine.store.stats()["skill_steps"] == 5

    asyncio.run(scenario())


def test_active_routine_degrades_when_the_app_changes(tmp_path: Path) -> None:
    async def scenario() -> None:
        engine = _engine(tmp_path)
        store = engine.store
        sid = store.create("routine", "uber_book", 1, "active", json.dumps(GENERAL), [], 1.0, 0)
        changed = RunTrace("new-ui", uber_run("x").goal, [
            {"screen": UBER[0].replace("Where to?", "Search destination"),
             "action": {"action": "tap", "id": 1}},
        ])  # fmt: skip
        calls: list[int] = []
        for i in range(2):
            changed.run_id = f"new-ui-{i}"
            handlers = await _drive(engine, changed, calls)
            assert handlers == ["llm-fallback"]  # the LLM takes over immediately
        assert store.get(sid).status == "degraded"  # type: ignore[union-attr]

    asyncio.run(scenario())


def test_tampered_skill_is_never_run(tmp_path: Path) -> None:
    async def scenario() -> None:
        engine = _engine(tmp_path)
        sid = engine.store.create("routine", "uber_book", 1, "active", json.dumps(GENERAL), [], 1.0, 0)
        engine.store.set(
            sid, body=json.dumps(GENERAL).replace("Where to?", "Pay now")
        )  # sha256 now mismatches
        calls: list[int] = []
        handlers = await _drive(engine, uber_run("t"), calls)
        assert not any(h.startswith("skill:") for h in handlers) and len(calls) == 5

    asyncio.run(scenario())


def test_guide_and_unknown_tasks_are_never_learned(tmp_path: Path) -> None:
    async def scenario() -> None:
        engine = _engine(tmp_path)

        async def llm_step() -> dict[str, Any]:
            return {"action": "highlight", "instruction": "Tap Display"}

        served = await engine.step(device="d-12345678", run_id="g1", task="settings_font_size", goal="font",
                                   mode="GUIDE", screen_text=UBER[0], llm_step=llm_step)  # fmt: skip
        assert served.handler == "llm" and engine.store.run("g1") is None

    asyncio.run(scenario())


def test_outcome_endpoint_and_skill_listing(client) -> None:  # noqa: ANN001
    h = {"X-Saathi-Device": "device-12345678"}
    assert (
        client.post("/agent/outcome", json={"run_id": "nope", "outcome": "done"}, headers=h).status_code
        == 200
    )
    r = client.get("/skills", headers=h)
    assert r.status_code == 200 and "stats" in r.json()
    assert client.post("/family/device-12345678/skills/1/approve?key=bad").status_code == 404
