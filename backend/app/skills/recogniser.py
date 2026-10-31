"""Code skills: the strong model writes Python that replaces an LLM route (docs/LEARNING.md §1, `answer` kind).

The first one is the voice intent router. Every LLM answer to `/intent` is kept as a training sample. Once there
are enough, gemini-4-argon writes `recognise(text) -> {"intent", "slots"} | None`, where None means "not sure,
ask the LLM". The code is statically checked, replay-tested in the Docker sandbox on held-out samples (precision
and coverage), approved by family, shadowed against the LLM, and only then answers for real. The spoken reply
always comes from fixed templates (`rules_intent.reply_for`), never from generated code.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, get_args

from app.schemas import Intent, IntentResponse, Slots
from app.skills.compiler import split
from app.skills.sandbox import Sandbox, static_check
from app.skills.store import Skill, SkillStore

log = logging.getLogger("saathi.skills.code")

KIND, NAME, ENTRY = "recogniser", "intent", "recognise"
INTENTS = set(get_args(Intent))
PRECISION = 0.95  # of the held-out samples it answers, this share must match the LLM exactly
COVERAGE = 0.3  # and it must answer at least this share (otherwise it saves nothing)
SERVE_TIMEOUT_S = 3.0


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _norm(v: Any) -> Any:
    return re.sub(r"\s+", " ", v).strip().casefold() if isinstance(v, str) else v


def normalise(value: Any) -> dict[str, Any] | None:
    """A skill's answer as {"intent", "slots"}, or None when it abstains or answers something invalid."""
    if not isinstance(value, dict) or value.get("intent") not in INTENTS:
        return None
    try:
        slots = Slots.model_validate(value.get("slots") or {})
    except Exception:  # noqa: BLE001
        return None
    return {"intent": value["intent"], "slots": slots.model_dump(exclude_none=True)}


def agrees(a: dict[str, Any], b: dict[str, Any]) -> bool:
    if a["intent"] != b["intent"]:
        return False
    sa, sb = a.get("slots") or {}, b.get("slots") or {}
    return {k: _norm(v) for k, v in sa.items()} == {k: _norm(v) for k, v in sb.items()}


@dataclass
class Score:
    total: int = 0
    answered: int = 0
    correct: int = 0
    wrong: list[str] = field(default_factory=list)

    @property
    def precision(self) -> float:
        return self.correct / self.answered if self.answered else 0.0

    @property
    def coverage(self) -> float:
        return self.answered / self.total if self.total else 0.0

    @property
    def passed(self) -> bool:
        return self.precision >= PRECISION and self.coverage >= COVERAGE


SYSTEM = f"""You write one small, deterministic Python function for a voice assistant used by elderly people in India.

`def {ENTRY}(text: str) -> dict | None` maps what the user said (English, Hindi in Devanagari, or Hinglish in Roman
script) to {{"intent": <one of {sorted(INTENTS)}>, "slots": {{...}}}}.
Slot keys: destination, product, specialty, doctor, area, time, date, service, quantity (int). Omit unknown slots.
Return None whenever you are not sure. A wrong answer is far worse than None, because None just asks a bigger model.
Rules: only `import re, json, datetime, math, string, unicodedata, difflib, collections, itertools`. No I/O, no
eval/exec/open/getattr/type, no dunder names, no lambdas, no globals. Generalise from the examples (keywords, slot
patterns); do not just memorise them. Reply with only the Python code."""


def build_prompt(samples: list[tuple[str, dict[str, Any]]], feedback: list[str]) -> str:
    lines = [json.dumps({"text": t, "answer": a}, ensure_ascii=False) for t, a in samples]
    out = "Examples (what the user said → the correct answer):\n" + "\n".join(lines)
    if feedback:
        out += "\n\nYour previous version was rejected:\n" + "\n".join(f"- {f}" for f in feedback[:12])
    return out


def strip_code(text: str) -> str:
    m = re.search(r"```(?:python|py)?\s*\n(.*?)```", text, re.DOTALL)
    return (m.group(1) if m else text).strip() + "\n"


class CodeSkills:
    def __init__(self, store: SkillStore, sandbox: Sandbox | None = None, *, min_samples: int | None = None,
                 shadow_agree: int | None = None, auto_approve: bool | None = None,
                 notify: Callable[[str], Awaitable[None]] | None = None) -> None:  # fmt: skip
        self.store = store
        self.sandbox = sandbox or Sandbox()
        self.min_samples = min_samples if min_samples is not None else _int_env("SKILL_MIN_SAMPLES", 40)
        self.shadow_agree = (
            shadow_agree if shadow_agree is not None else _int_env("SKILL_CODE_SHADOW_AGREE", 20)
        )
        env_auto = os.environ.get("SKILL_AUTO_APPROVE", "0").strip().lower() in {"1", "true", "yes"}
        self.auto_approve = env_auto if auto_approve is None else auto_approve
        self.notify = notify
        self._bg: set[asyncio.Task[Any]] = set()
        self._compiling = False

    # ------------------------------------------------------------------ serving
    async def intent(self, utterance: str, style: str,
                     ask_llm: Callable[[], Awaitable[IntentResponse]]) -> tuple[IntentResponse, str]:  # fmt: skip
        """Answer /intent from the active skill when it is sure; otherwise ask the LLM and learn from it."""
        from app.llm.rules_intent import reply_for

        skill = self._live()
        if skill and skill.status == "active":
            got = await self._run(skill, utterance)
            if got is not None:
                slots = Slots.model_validate(got["slots"])
                reply = reply_for(got["intent"], slots, style)
                self.store.set(skill.id, runs=skill.runs + 1)
                return IntentResponse(
                    intent=got["intent"], slots=slots, reply_style=style, reply_text=reply
                ), (f"skill:{skill.id}")
        res = await ask_llm()
        truth = {"intent": res.intent, "slots": res.slots.model_dump(exclude_none=True)}
        if self.sandbox.enabled and len(utterance) <= 300:
            self.store.add_sample(NAME, utterance, truth)
        if skill and skill.status == "shadow":
            self._spawn(self._shadow(skill, utterance, truth))
        return res, "llm"

    def _live(self) -> Skill | None:
        if not self.sandbox.enabled:
            return None
        skill = self.store.live_skill(KIND, NAME)
        if skill and not skill.verify():
            log.error(
                "code skill %s@v%d failed its sha256 check; refusing to run it", skill.name, skill.version
            )
            return None
        return skill

    async def _run(self, skill: Skill, text: str) -> dict[str, Any] | None:
        try:
            [res] = await asyncio.wait_for(self.sandbox.run(skill.body, ENTRY, [text]), SERVE_TIMEOUT_S)
        except Exception:  # noqa: BLE001 - slow or broken sandbox: the LLM answers
            return None
        return normalise(res.get("value")) if res.get("ok") else None

    async def _shadow(self, skill: Skill, text: str, truth: dict[str, Any]) -> None:
        got = await self._run(skill, text)
        if got is None:
            return  # abstaining is always safe
        fresh = self.store.get(skill.id)
        if not fresh or fresh.status != "shadow":
            return
        if not agrees(got, truth):
            self.store.set(skill.id, status="degraded", last_error="disagreed with the LLM in shadow")
            self.store.event(skill.id, "shadow_disagree", skill=got["intent"], llm=truth["intent"])
            return
        matches = fresh.shadow_matches + 1
        self.store.set(skill.id, shadow_matches=matches)
        if matches >= self.shadow_agree:
            self.store.set(skill.id, status="active")
            self.store.retire_older(KIND, NAME, skill.version)
            self.store.event(skill.id, "activated", shadow_matches=matches)
            await self._tell(f"Saathi now understands common requests on its own (v{skill.version}), "
                             "so replies are faster and cheaper.")  # fmt: skip

    def _spawn(self, coro: Awaitable[Any]) -> None:
        task = asyncio.ensure_future(coro)
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    # ------------------------------------------------------------------ compiling
    def due(self) -> bool:
        if not self.sandbox.enabled or self.store.sample_count(NAME) < self.min_samples:
            return False
        latest = self.store.latest(KIND, NAME)
        if latest is None or latest.status in {"degraded", "retired"}:
            return True
        return latest.status == "candidate" and self.store.sample_count(NAME) >= 2 * len(latest.trained_on)

    async def score(self, code: str, samples: list[tuple[str, dict[str, Any]]]) -> Score:
        results = await self.sandbox.run(code, ENTRY, [t for t, _ in samples])
        s = Score(total=len(samples))
        for (text, truth), res in zip(samples, results, strict=True):
            got = normalise(res.get("value")) if res.get("ok") else None
            if got is None:
                continue
            s.answered += 1
            if agrees(got, truth):
                s.correct += 1
            else:
                s.wrong.append(f"{text!r}: you said {got}, correct is {truth}")
        return s

    async def compile(self, llm: Any) -> int | None:
        generate = getattr(llm, "compile_json", None)
        if generate is None or self._compiling or not self.due():
            return None
        self._compiling = True
        try:
            return await self._compile(generate)
        finally:
            self._compiling = False

    async def _compile(self, generate: Callable[..., Awaitable[tuple[str, int]]]) -> int | None:
        samples = self.store.samples(NAME)
        train, held = split(samples, seed=f"intent@{len(samples)}")  # type: ignore[arg-type]
        latest = self.store.latest(KIND, NAME)
        version = (latest.version + 1) if latest else 1
        feedback: list[str] = []
        tokens = 0
        code, held_score = "", Score()
        for _ in range(2):
            try:
                text, used = await generate("skill_compiler", SYSTEM, build_prompt(train, feedback))
            except Exception as exc:  # noqa: BLE001
                feedback = [f"generation failed: {type(exc).__name__}"]
                continue
            tokens += used
            code = strip_code(text)
            problems = static_check(code, ENTRY)
            if problems:
                feedback = problems
                continue
            train_score = await self.score(code, train)
            held_score = await self.score(code, held)
            if train_score.passed and held_score.passed:
                break
            feedback = [f"precision {train_score.precision:.2f}, coverage {train_score.coverage:.2f} on examples",
                        *train_score.wrong[:10]]  # fmt: skip
        else:
            sid = self.store.create(KIND, NAME, version, "candidate", "", [t for t, _ in train], held_score.precision,
                                    tokens, last_error="; ".join(feedback[:4]) or "compile failed")  # fmt: skip
            self.store.event(sid, "compile_failed", problems=feedback[:4])
            return None
        status = "shadow" if self.auto_approve else "tested"
        sid = self.store.create(
            KIND, NAME, version, status, code, [t for t, _ in train], held_score.precision, tokens
        )
        self.store.event(sid, "compiled", version=version, precision=held_score.precision,
                         coverage=held_score.coverage, tokens=tokens)  # fmt: skip
        if status == "tested":
            await self._tell(f"Saathi wrote code to understand common requests by itself (v{version}): right on "
                             f"{held_score.precision:.0%} of the test requests it answered, and it answered "
                             f"{held_score.coverage:.0%} of them. Approve it on the family page.")  # fmt: skip
        return sid

    async def _tell(self, text: str) -> None:
        if self.notify:
            try:
                await self.notify(text)
            except Exception:  # noqa: BLE001
                log.info("code skill notification failed")
