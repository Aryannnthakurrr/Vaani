"""The learning loop at runtime: serve agent steps from compiled routines, shadow-test them, track their health, and
compile new versions in the background (docs/features/LEARNING.md §1)."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app.skills.compiler import RunTrace, compile_routine
from app.skills.routine import Routine, propose, same_action
from app.skills.store import Skill, SkillStore

log = logging.getLogger("saathi.skills")


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass
class Config:
    min_runs: int = 3  # successful LLM runs before a task becomes a candidate
    shadow_agree: int = 3  # fully agreeing shadow runs before a routine goes active
    window: int = 5  # rolling health window for active routines
    max_bad: int = 2  # bad runs in the window that degrade an active routine
    canary_every: int = 20  # 1 in N active steps is double-checked by the LLM in the background
    auto_approve: bool = False

    @classmethod
    def from_env(cls) -> Config:
        return cls(
            min_runs=_int_env("SKILL_MIN_RUNS", 3),
            shadow_agree=_int_env("SKILL_SHADOW_AGREE", 3),
            canary_every=_int_env("SKILL_CANARY_EVERY", 20),
            auto_approve=os.environ.get("SKILL_AUTO_APPROVE", "0").strip().lower() in {"1", "true", "yes"},
        )


LlmStep = Callable[[], Awaitable[dict[str, Any]]]


@dataclass
class Served:
    action: dict[str, Any]
    handler: str  # "skill:<id>" | "llm" | "llm-fallback"


class SkillEngine:
    def __init__(self, store: SkillStore, config: Config | None = None,
                 notify: Callable[[str], Awaitable[None]] | None = None) -> None:  # fmt: skip
        self.store = store
        self.config = config or Config.from_env()
        self.notify = notify
        self._compiling: set[str] = set()

    # ------------------------------------------------------------------ serving
    async def step(self, *, device: str, run_id: str | None, task: str | None, goal: str, mode: str,
                   screen_text: str, llm_step: LlmStep) -> Served:  # fmt: skip
        """Answer one agent step. Learning only applies to ACT runs of a known task with a run id."""
        if not run_id or not task or mode != "ACT":
            return Served(await llm_step(), "llm")
        run = self.store.start_run(run_id, device, task, goal, mode)
        skill = self.store.live_skill("routine", task)
        routine = _load(skill)
        pos = run["routine_pos"]
        proposal = propose(routine, screen_text, goal, pos) if routine else None

        if skill and routine and skill.status == "active":
            self.store.update_run(run_id, skill_id=skill.id, skill_role="active")
            if proposal is not None and proposal.action is not None:
                action = {
                    **proposal.action,
                    "reason": f"skill {skill.name}@v{skill.version} step {proposal.step_index + 1}",
                }
                self.store.update_run(
                    run_id, routine_pos=proposal.step_index + 1, skill_steps=run["skill_steps"] + 1
                )
                self.store.add_step(run_id, screen_text, action, f"skill:{skill.id}")
                if self.config.canary_every and random.randrange(self.config.canary_every) == 0:
                    asyncio.create_task(self._canary(skill, run_id, action, llm_step))
                if action["action"] == "done":
                    self.store.update_run(run_id, outcome="done")  # provisional; /agent/outcome overrides
                    await self._settle(run_id)
                return Served(action, f"skill:{skill.id}")
            action = await llm_step()
            if proposal is not None:  # an "llm" step: planned delegation, not a miss
                self.store.update_run(
                    run_id, routine_pos=proposal.step_index + 1, llm_steps=run["llm_steps"] + 1
                )
                self.store.add_step(run_id, screen_text, action, "llm")
            else:
                self.store.update_run(run_id, misses=run["misses"] + 1, llm_steps=run["llm_steps"] + 1)
                self.store.add_step(run_id, screen_text, action, "llm-fallback")
            return Served(action, "llm" if proposal else "llm-fallback")

        action = await llm_step()
        fields: dict[str, Any] = {"llm_steps": run["llm_steps"] + 1}
        if skill and routine and skill.status == "shadow":
            fields.update(skill_id=skill.id, skill_role="shadow")
            if proposal is None or (proposal.action is not None and not same_action(proposal.action, action)):
                fields["shadow_ok"] = 0
            elif proposal is not None:
                fields["routine_pos"] = proposal.step_index + 1
        self.store.update_run(run_id, **fields)
        self.store.add_step(run_id, screen_text, action, "llm")
        if action.get("action") == "done" and run["outcome"] is None:
            self.store.update_run(run_id, outcome="done")  # provisional; /agent/outcome overrides
            await self._settle(run_id)
        return Served(action, "llm")

    async def _canary(self, skill: Skill, run_id: str, served: dict[str, Any], llm_step: LlmStep) -> None:
        try:
            truth = await llm_step()
        except Exception:  # noqa: BLE001 - a canary must never break the run
            return
        if not same_action(served, truth):
            self.store.event(skill.id, "canary_disagree", run_id=run_id, skill=served.get("action"),
                             llm=truth.get("action"))  # fmt: skip
            run = self.store.run(run_id)
            if run:
                self.store.update_run(run_id, misses=run["misses"] + 1)

    # ------------------------------------------------------------------ outcomes and lifecycle
    async def outcome(self, run_id: str, outcome: str) -> None:
        if not self.store.run(run_id):
            return
        self.store.update_run(run_id, outcome=outcome)
        await self._settle(run_id)

    async def _settle(self, run_id: str) -> None:
        run = self.store.run(run_id)
        if not run or not run["skill_id"] or run["settled"]:
            return
        self.store.update_run(
            run_id, settled=1
        )  # a provisional "done" and the phone's outcome must not both count
        skill = self.store.get(run["skill_id"])
        if not skill:
            return
        good = run["outcome"] == "done" and run["misses"] == 0
        if run["skill_role"] == "active" and skill.status == "active":
            recent = (skill.recent + [1 if good else 0])[-self.config.window :]
            self.store.set(skill.id, recent=recent, runs=skill.runs + 1)
            if recent.count(0) >= self.config.max_bad:
                self.store.set(
                    skill.id,
                    status="degraded",
                    last_error=f"{recent.count(0)} bad runs of last {len(recent)}",
                )
                self.store.event(skill.id, "degraded", recent=recent)
                log.warning("skill %s@v%d degraded; the LLM takes over", skill.name, skill.version)
        elif run["skill_role"] == "shadow" and skill.status == "shadow" and run["outcome"] == "done":
            if run["shadow_ok"]:
                matches = skill.shadow_matches + 1
                self.store.set(skill.id, shadow_matches=matches)
                if matches >= self.config.shadow_agree:
                    self.store.set(skill.id, status="active")
                    self.store.retire_older("routine", skill.name, skill.version)
                    self.store.event(skill.id, "activated", shadow_matches=matches)
                    await self._tell(f"Vaani has learned to do '{skill.name}' on its own (v{skill.version}). "
                                     "It will be faster and cheaper from now on.")  # fmt: skip
            else:
                self.store.set(skill.id, status="degraded", last_error="disagreed with the LLM in shadow")
                self.store.event(skill.id, "shadow_disagree", run_id=run_id)

    def approve(self, skill_id: int, actor: str) -> Skill | None:
        skill = self.store.get(skill_id)
        if not skill or skill.status != "tested":
            return None
        self.store.set(skill_id, status="shadow", shadow_matches=0, approved_by=actor)
        self.store.event(skill_id, "approved", actor=actor)
        return self.store.get(skill_id)

    def reject(self, skill_id: int, actor: str) -> Skill | None:
        skill = self.store.get(skill_id)
        if not skill or skill.status not in {"tested", "shadow", "active"}:
            return None
        self.store.set(skill_id, status="retired", approved_by=actor)
        self.store.event(skill_id, "rejected", actor=actor)
        return self.store.get(skill_id)

    # ------------------------------------------------------------------ compiling
    def due(self) -> list[str]:
        """Tasks that need a (new) routine: enough successful runs and no healthy live version."""
        out: list[str] = []
        for task, n in self.store.tasks_with_runs(self.config.min_runs):
            latest = self.store.latest("routine", task)
            if latest is None or latest.status in {"degraded", "retired"}:
                out.append(task)
            elif latest.status == "candidate" and n > len(latest.trained_on):
                out.append(task)  # failed before; retry with more runs
        return out

    async def compile_pending(self, llm: Any) -> list[int]:
        made: list[int] = []
        for task in self.due():
            if task in self._compiling:
                continue
            self._compiling.add(task)
            try:
                skill_id = await self.compile_task(task, llm)
                if skill_id:
                    made.append(skill_id)
            finally:
                self._compiling.discard(task)
        return made

    async def compile_task(self, task: str, llm: Any) -> int | None:
        rows = self.store.successful_runs(task)
        runs = [RunTrace(r["run_id"], r["goal"], _clean_steps(self.store.steps(r["run_id"]))) for r in rows]
        runs = [r for r in runs if r.steps]
        if len(runs) < self.config.min_runs:
            return None
        latest = self.store.latest("routine", task)
        version = (latest.version + 1) if latest else 1
        res = await compile_routine(task, runs, llm, seed=f"{task}@{version}")
        ids = [r.run_id for r in runs]
        if res.routine is None or not res.visible_ok or (res.held_out_pass or 1.0) < 0.9:
            sid = self.store.create("routine", task, version, "candidate", "", ids, res.held_out_pass, res.tokens,
                                    last_error="; ".join(res.problems[:5]) or "compile failed")  # fmt: skip
            self.store.event(sid, "compile_failed", problems=res.problems[:5])
            return None
        body = res.routine.model_dump_json(exclude_none=True)
        status = "shadow" if self.config.auto_approve else "tested"
        sid = self.store.create("routine", task, version, status, body, ids, res.held_out_pass, res.tokens)
        self.store.event(sid, "compiled", version=version, held_out_pass=res.held_out_pass, tokens=res.tokens,
                         auto_approved=self.config.auto_approve)  # fmt: skip
        if status == "tested":
            await self._tell(f"Vaani learned a new routine for '{task}' (v{version}), tested on "
                             f"{len(ids)} past runs. Approve it on the family page so Vaani can start using it.")  # fmt: skip
        return sid

    async def _tell(self, text: str) -> None:
        if self.notify:
            try:
                await self.notify(text)
            except Exception:  # noqa: BLE001 - notifications are best effort
                log.info("skill notification failed")


def _load(skill: Skill | None) -> Routine | None:
    if skill is None or not skill.body:
        return None
    if not skill.verify():
        log.error("skill %s@v%d failed its sha256 check; refusing to run it", skill.name, skill.version)
        return None
    try:
        return Routine.model_validate(json.loads(skill.body))
    except Exception:  # noqa: BLE001
        return None


def _clean_steps(steps: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Training steps: drop the model's free-text reason; keep the screen and the action."""
    out = []
    for s in steps:
        action = {k: v for k, v in s["action"].items() if k != "reason"}
        if isinstance(action.get("then"), dict):
            action["then"] = {k: v for k, v in action["then"].items() if k != "reason"}
        out.append({"screen": s["screen"], "action": action})
    return out
