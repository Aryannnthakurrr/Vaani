"""Routines: declarative, compiled phone-automation skills (docs/features/LEARNING.md §1).

A routine is a list of steps, each saying which screen it expects and which element to act on, located by text, not by
the per-snapshot ID. Everything here is pure: parse the serialized screen, find the step for the current
screen, resolve its target to today's node ID, fill slots from the goal. Safety rules are re-checked in `validate()`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

# --------------------------------------------------------------------------- screen parsing

_LINE = re.compile(r"^\s*\[(\d+)\]\s+(\S+)(.*)$")
_QUOTED = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"')
_ATTR = re.compile(r'(desc|hint)="((?:[^"\\]|\\.)*)"')
_FLAGS = ("clickable", "editable", "scrollable", "checked", "unchecked", "focused", "password")


@dataclass(frozen=True)
class Node:
    id: int
    cls: str
    text: str
    desc: str
    hint: str
    flags: frozenset[str]

    @property
    def label(self) -> str:
        return self.text or self.desc or self.hint


def parse_screen(screen_text: str) -> list[Node]:
    """Parse the serializer's `[id] Cls "text" desc=".." hint=".." flags bounds=(..)` lines (docs/engineering/CONTRACTS.md)."""
    nodes: list[Node] = []
    for line in screen_text.splitlines():
        m = _LINE.match(line)
        if not m:
            continue
        rest = m.group(3)
        q = _QUOTED.match(rest)
        text = q.group(1) if q else ""
        attrs = dict(_ATTR.findall(rest))
        tail = rest.split(" bounds=")[0]
        flags = frozenset(f for f in _FLAGS if re.search(rf"\b{f}\b", tail))
        nodes.append(
            Node(int(m.group(1)), m.group(2), text, attrs.get("desc", ""), attrs.get("hint", ""), flags)
        )
    return nodes


def screen_strings(nodes: list[Node]) -> str:
    return "\n".join(f"{n.text}\n{n.desc}\n{n.hint}" for n in nodes).lower()


# --------------------------------------------------------------------------- routine model

ActionName = Literal["tap", "type", "scroll", "back", "wait", "ask_user", "confirm", "done", "llm"]
# Actions a routine may NEVER contain: launching arbitrary apps/links, dialling, coordinates.
FORBIDDEN = {"open_app", "open_deeplink", "tap_xy", "home", "highlight"}
COMMIT_WORDS = re.compile(
    r"(?i)\b(book|booking|confirm|pay|place order|buy now|proceed to pay|send|request|schedule|add to cart)\b"
    r"|बुक|पुष्टि|भुगतान|ऑर्डर|भेजें|कार्ट में"
)


class Target(BaseModel):
    """How to find an element on today's screen. All string fields may contain `{slot}` placeholders."""

    text: str | None = None
    text_contains: str | None = None
    text_regex: str | None = None
    hint: str | None = None
    desc: str | None = None
    cls: str | None = None
    editable: bool | None = None
    index: int = 0

    @model_validator(mode="after")
    def _one_selector(self) -> Target:
        if not any([self.text, self.text_contains, self.text_regex, self.hint, self.desc]):
            raise ValueError("target needs at least one of text, text_contains, text_regex, hint, desc")
        return self


class Inner(BaseModel):
    action: Literal["tap"]
    target: Target


class Step(BaseModel):
    expect: list[str] = Field(default_factory=list, max_length=6)
    action: ActionName
    target: Target | None = None
    text: str | None = None
    direction: Literal["up", "down"] | None = None
    ms: int | None = None
    question: str | None = None
    summary: str | None = None
    message: str | None = None
    then: Inner | None = None
    read: dict[str, Target] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _shape(self) -> Step:
        need_target = {"tap", "type"}
        if self.action in need_target and self.target is None:
            raise ValueError(f"{self.action} needs a target")
        if self.action == "type" and self.text is None:
            raise ValueError("type needs text")
        if self.action == "confirm" and (self.then is None or not self.summary):
            raise ValueError("confirm needs summary and then")
        if self.action == "done" and not self.message:
            raise ValueError("done needs message")
        if self.action == "ask_user" and not self.question:
            raise ValueError("ask_user needs question")
        return self


class Routine(BaseModel):
    task: str
    slots: dict[str, str] = Field(
        default_factory=dict, description="slot -> regex with one group, run on the goal"
    )
    steps: list[Step] = Field(min_length=1, max_length=40)

    @field_validator("slots")
    @classmethod
    def _slot_regexes(cls, v: dict[str, str]) -> dict[str, str]:
        for name, pattern in v.items():
            if re.compile(pattern).groups < 1:
                raise ValueError(f"slot {name} regex needs a capture group")
        return v


def validate(routine: Routine) -> list[str]:
    """Safety checks beyond the schema. Returns a list of problems (empty = OK)."""
    problems: list[str] = []
    for i, s in enumerate(routine.steps, 1):
        if s.action in FORBIDDEN:
            problems.append(f"step {i}: action {s.action} is not allowed in routines")
        if s.action == "tap" and s.target and _looks_like_commit(s.target):
            problems.append(f"step {i}: tapping a commit button must be wrapped in confirm")
        if s.action == "type" and s.text and re.search(r"[*#][0-9*#]{2,}", s.text):
            problems.append(f"step {i}: routines never type dial codes")
        if (
            s.action == "type"
            and s.target
            and (s.target.hint or "").lower() in {"password", "upi pin", "pin", "otp"}
        ):
            problems.append(f"step {i}: routines never type into PIN/password/OTP fields")
    return problems


def _looks_like_commit(t: Target) -> bool:
    return any(COMMIT_WORDS.search(v or "") for v in (t.text, t.text_contains, t.text_regex, t.desc))


# --------------------------------------------------------------------------- runtime


def fill(template: str | None, values: dict[str, str]) -> str:
    if template is None:
        return ""
    return re.sub(r"\{(\w+)\}", lambda m: values.get(m.group(1), m.group(0)), template)


def extract_slots(routine: Routine, goal: str) -> dict[str, str] | None:
    """Slot values from the goal; None if any slot is missing (the routine can't run for this goal)."""
    values: dict[str, str] = {}
    for name, pattern in routine.slots.items():
        m = re.search(pattern, goal, re.IGNORECASE)
        if not m or not m.group(1).strip():
            return None
        values[name] = m.group(1).strip()
    return values


def resolve(target: Target, nodes: list[Node], values: dict[str, str]) -> Node | None:
    def norm(s: str) -> str:
        return " ".join(s.lower().split())

    found: list[Node] = []
    for n in nodes:
        if target.cls and n.cls != target.cls:
            continue
        if target.editable is not None and ("editable" in n.flags) != target.editable:
            continue
        if target.text is not None and norm(n.text) != norm(fill(target.text, values)):
            continue
        if target.text_contains is not None and norm(fill(target.text_contains, values)) not in norm(n.label):
            continue
        if target.text_regex is not None and not re.search(fill(target.text_regex, values), n.label, re.I):
            continue
        if target.hint is not None and norm(n.hint) != norm(fill(target.hint, values)):
            continue
        if target.desc is not None and norm(fill(target.desc, values)) not in norm(n.desc):
            continue
        found.append(n)
    return found[target.index] if 0 <= target.index < len(found) else None


def _expect_met(step: Step, haystack: str, values: dict[str, str]) -> bool:
    return all(fill(e, values).lower() in haystack for e in step.expect)


def _read(step: Step, nodes: list[Node], values: dict[str, str]) -> dict[str, str] | None:
    out = dict(values)
    for key, tgt in step.read.items():
        n = resolve(tgt, nodes, values)
        if n is None:
            return None
        if tgt.text_regex:
            m = re.search(fill(tgt.text_regex, values), n.label, re.I)
            out[key] = (m.group(1) if m and m.groups() else m.group(0)) if m else n.label
        else:
            out[key] = n.label
    return out


@dataclass
class Proposal:
    step_index: int
    action: dict[str, Any] | None  # None = this step is delegated to the LLM ("llm" step)


def propose(
    routine: Routine, screen_text: str, goal: str, position: int, lookahead: int = 2
) -> Proposal | None:
    """The routine's action for this screen, or None if no step fits (a routine miss)."""
    values = extract_slots(routine, goal)
    if values is None:
        return None
    nodes = parse_screen(screen_text)
    header = screen_text.split("\n", 1)[0] if screen_text.startswith("package=") else ""
    hay = screen_strings(nodes) + "\n" + header.lower()  # window title counts as visible text
    for i in range(position, min(position + 1 + lookahead, len(routine.steps))):
        step = routine.steps[i]
        if not _expect_met(step, hay, values):
            continue
        if step.action == "llm":
            return Proposal(i, None)
        action = _to_action(step, nodes, values)
        if action is not None:
            return Proposal(i, action)
    return None


def _to_action(step: Step, nodes: list[Node], values: dict[str, str]) -> dict[str, Any] | None:
    vals = _read(step, nodes, values)
    if vals is None:
        return None
    a = step.action
    if a in {"tap", "type"}:
        n = resolve(step.target, nodes, vals)  # type: ignore[arg-type]
        if n is None:
            return None
        if a == "tap":
            return {"action": "tap", "id": n.id}
        return {"action": "type", "id": n.id, "text": fill(step.text, vals)}
    if a == "scroll":
        return {"action": "scroll", "direction": step.direction or "down"}
    if a == "back":
        return {"action": "back"}
    if a == "wait":
        return {"action": "wait", "ms": step.ms or 1000}
    if a == "ask_user":
        return {"action": "ask_user", "question": fill(step.question, vals)}
    if a == "confirm":
        n = resolve(step.then.target, nodes, vals)  # type: ignore[union-attr]
        if n is None:
            return None
        return {
            "action": "confirm",
            "summary": fill(step.summary, vals),
            "then": {"action": "tap", "id": n.id},
        }
    if a == "done":
        return {"action": "done", "message": fill(step.message, vals)}
    return None


def same_action(a: dict[str, Any] | None, b: dict[str, Any] | None) -> bool:
    """Do two actions do the same thing on the same screen? (shadow comparison and replay tests)"""
    if a is None or b is None:
        return a is b
    if a.get("action") != b.get("action"):
        return False
    kind = a["action"]
    if kind == "tap":
        return a.get("id") == b.get("id")
    if kind == "type":
        return a.get("id") == b.get("id") and _norm(a.get("text")) == _norm(b.get("text"))
    if kind == "confirm":
        return (a.get("then") or {}).get("id") == (b.get("then") or {}).get("id")
    if kind == "scroll":
        return a.get("direction") == b.get("direction")
    return True  # back, wait, ask_user, done: same kind is enough


def _norm(s: Any) -> str:
    return " ".join(str(s or "").lower().split())
