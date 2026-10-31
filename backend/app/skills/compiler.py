"""Compile successful agent runs into a routine, then prove it by replaying runs the compiler never saw.

The strong model (`skill_compiler` route) writes the routine; `heuristic_routine` is a model-free fallback used in mock
mode and as a baseline. Every candidate is schema-checked, safety-checked (`routine.validate`) and replay-tested.
"""

from __future__ import annotations

import json
import logging
import random
from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from app.skills.routine import Routine, parse_screen, propose, same_action, validate

log = logging.getLogger("saathi.skills.compiler")

HELD_OUT_SHARE = 0.3
HELD_OUT_PASS = 0.9
MAX_SCREEN_LINES = 70


@dataclass
class RunTrace:
    run_id: str
    goal: str
    steps: list[dict[str, Any]]  # [{"screen": str, "action": {...}}]


@dataclass
class CompileResult:
    routine: Routine | None
    visible_ok: bool = False
    held_out_pass: float | None = None
    problems: list[str] = field(default_factory=list)
    tokens: int = 0


def split(runs: list[RunTrace], seed: str) -> tuple[list[RunTrace], list[RunTrace]]:
    """Deterministic held-out split: 30% of runs (at least 1 when there are 3+), at least 1 visible."""
    if len(runs) < 3:
        return runs, []
    shuffled = runs[:]
    random.Random(seed).shuffle(shuffled)
    k = max(1, round(len(runs) * HELD_OUT_SHARE))
    return shuffled[k:], shuffled[:k]


def replay(routine: Routine, run: RunTrace) -> tuple[bool, str]:
    """Would the routine have produced each recorded action on each recorded screen?"""
    pos = 0
    for i, step in enumerate(run.steps):
        proposal = propose(routine, step["screen"], run.goal, pos)
        if proposal is None:
            return False, f"step {i + 1}: no routine step fits this screen"
        if proposal.action is not None and not same_action(proposal.action, step["action"]):
            return (
                False,
                f"step {i + 1}: routine did {json.dumps(proposal.action)} but run did {json.dumps(step['action'])}",
            )
        pos = proposal.step_index + 1
    return True, "ok"


def evaluate(routine: Routine, visible: list[RunTrace], held_out: list[RunTrace]) -> CompileResult:
    problems = validate(routine)
    vis_fail = [f"{r.run_id}: {why}" for r in visible for ok, why in [replay(routine, r)] if not ok]
    problems += vis_fail
    held = [replay(routine, r)[0] for r in held_out]
    rate = (sum(held) / len(held)) if held else None
    return CompileResult(routine, visible_ok=not vis_fail, held_out_pass=rate, problems=problems)


def passed(res: CompileResult) -> bool:
    return (
        res.routine is not None
        and res.visible_ok
        and not [p for p in res.problems if "not allowed" in p or "must be wrapped" in p or "never" in p]
        and (res.held_out_pass is None or res.held_out_pass >= HELD_OUT_PASS)
    )


# --------------------------------------------------------------------------- model-free baseline


def heuristic_routine(task: str, run: RunTrace) -> Routine | None:
    """Literal routine from one run: target each acted-on element by its label. No slots, so it only generalises to
    the same goal; good enough for mock mode, and a fallback when the strong model is unavailable."""
    steps: list[dict[str, Any]] = []
    for s in run.steps:
        nodes = {n.id: n for n in parse_screen(s["screen"])}
        a = s["action"]
        kind = a.get("action")
        if kind in {"tap", "type"}:
            n = nodes.get(a.get("id"))
            if n is None or not n.label:
                return None
            tgt = {"text": n.text} if n.text else ({"hint": n.hint} if n.hint else {"desc": n.desc})
            step: dict[str, Any] = {"expect": [n.label], "action": kind, "target": tgt}
            if kind == "type":
                step["text"] = a.get("text", "")
                step["target"]["editable"] = True
            steps.append(step)
        elif kind == "confirm":
            n = nodes.get((a.get("then") or {}).get("id"))
            if n is None or not n.label:
                return None
            steps.append({"expect": [n.label], "action": "confirm", "summary": a.get("summary", "Shall I go ahead?"),
                          "then": {"action": "tap", "target": {"text": n.text or n.label}}})  # fmt: skip
        elif kind == "done":
            steps.append({"expect": [], "action": "done", "message": a.get("message", "Done.")})
        elif kind in {"ask_user"}:
            steps.append({"expect": [], "action": "llm"})
        elif kind in {"scroll", "back", "wait"}:
            steps.append({"expect": [], "action": "llm"})
    try:
        return Routine(task=task, slots={}, steps=steps)
    except ValidationError:
        return None


# --------------------------------------------------------------------------- strong-model compiler

SYSTEM = """You compile phone-automation ROUTINES for Saathi, an assistant for elderly users.
You get several successful runs of the same task. Each run is a goal and a list of steps; each step shows the screen
(one line per element: [id] Class "text" desc=".." hint=".." flags) and the action the agent took.

Write ONE routine (JSON) that reproduces these runs and generalises to new goals of the same task.

Rules:
- Output JSON: {"task": str, "slots": {name: regex with exactly one capture group, matched against the goal},
  "steps": [Step, ...]}.
- Step: {"expect": [strings that must all be visible], "action": one of tap|type|scroll|back|wait|ask_user|confirm|done|llm,
  "target": Target (tap/type), "text": (type; may use {slot}), "summary"/"then" (confirm), "message" (done),
  "question" (ask_user), "read": {var: Target} to capture on-screen text into {var} for summary/message}.
- Target: {"text"|"text_contains"|"text_regex"|"hint"|"desc": value, "cls"?: str, "editable"?: bool, "index"?: int}.
  NEVER use element ids: they change every time. Use stable labels; use {slot} placeholders for values that vary
  with the goal (destinations, product names, doctor names).
- A tap on any button that books, confirms, pays, orders, sends or adds to cart MUST be a "confirm" step whose
  "then" taps it; the summary must state price or details captured with "read".
- Where a run needed judgement (choosing among options the user picked, recovering from a popup), use
  {"action":"llm"}: the live model handles that step.
- Never use open_app, open_deeplink, tap_xy, home, highlight; never type passwords, PINs, OTPs or dial codes.
- "expect" strings must be stable text present on that screen in EVERY run (not prices, not times).
Return only the JSON."""


def build_prompt(task: str, runs: list[RunTrace], feedback: str | None = None) -> str:
    parts = [f"TASK: {task}"]
    for i, r in enumerate(runs, 1):
        parts.append(f"\n=== RUN {i} ===\nGOAL: {r.goal}")
        for j, s in enumerate(r.steps, 1):
            screen = "\n".join(s["screen"].splitlines()[:MAX_SCREEN_LINES])
            parts.append(
                f"--- step {j} screen ---\n{screen}\n--- step {j} action ---\n{json.dumps(s['action'], ensure_ascii=False)}"
            )
    if feedback:
        parts.append(f"\nYOUR PREVIOUS ROUTINE FAILED THESE CHECKS; fix them:\n{feedback}")
    return "\n".join(parts)


async def compile_routine(task: str, runs: list[RunTrace], llm: Any, seed: str) -> CompileResult:
    """Two attempts with the strong model (failures fed back), then the heuristic baseline."""
    visible, held_out = split(runs, seed)
    feedback: str | None = None
    tokens = 0
    best = CompileResult(None, problems=["not compiled"])
    generate = getattr(llm, "compile_json", None)
    for _attempt in range(2 if generate else 0):
        try:
            raw, used = await generate("skill_compiler", SYSTEM, build_prompt(task, visible, feedback))
            tokens += used
            routine = Routine.model_validate(json.loads(raw))
        except Exception as exc:  # invalid JSON / schema / model unavailable
            feedback = f"invalid output: {type(exc).__name__}: {str(exc)[:500]}"
            log.info("compile %s attempt failed: %s", task, type(exc).__name__)
            continue
        res = evaluate(routine, visible, held_out)
        res.tokens = tokens
        if passed(res):
            return res
        best, feedback = (
            res,
            "\n".join(res.problems[:12]) or "held-out runs failed: generalise with {slot} targets",
        )
    for r in visible:
        routine = heuristic_routine(task, r)
        if routine is None:
            continue
        res = evaluate(routine, visible, held_out)
        res.tokens = tokens
        if passed(res):
            return res
    best.tokens = tokens
    return best
