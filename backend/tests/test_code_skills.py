"""Code skills: static check, the sandbox runner (as a local subprocess), and the intent recogniser lifecycle."""

from __future__ import annotations

import pytest

from app.schemas import IntentResponse, Slots
from app.skills.recogniser import CodeSkills, agrees, normalise, strip_code
from app.skills.sandbox import Sandbox, static_check
from app.skills.store import SkillStore

GOOD = """
import re

def recognise(text):
    t = text.lower()
    if re.search(r"\\b(cab|taxi|uber|ola)\\b", t):
        m = re.search(r"\\bto ([a-z0-9 ]+?)\\s*$", t)
        return {"intent": "book_cab", "slots": {"destination": m.group(1).title()} if m else {}}
    if "doctor" in t:
        return {"intent": "book_doctor", "slots": {}}
    return None
"""

WRONG = """
def recognise(text):
    return {"intent": "book_cab", "slots": {}}
"""


@pytest.mark.parametrize(
    "code,needle",
    [
        ("import os\ndef recognise(t):\n    return None\n", "import of os"),
        ("def recognise(t):\n    return open('/etc/passwd').read()\n", "use of open"),
        ("def recognise(t):\n    return ().__class__.__bases__\n", "dunder attribute"),
        ("def recognise(t):\n    return eval(t)\n", "use of eval"),
        ("from subprocess import run\ndef recognise(t):\n    return None\n", "import from subprocess"),
        ("def other(t):\n    return None\n", "no top-level function recognise"),
        ("def recognise(t:\n", "syntax error"),
    ],
)
def test_static_check_rejects_hostile_or_broken_code(code, needle):
    assert any(needle in p for p in static_check(code, "recognise"))


def test_static_check_accepts_plain_code():
    assert static_check(GOOD, "recognise") == []


async def test_subprocess_sandbox_runs_cases_in_isolation():
    sb = Sandbox(mode="subprocess")
    res = await sb.run(GOOD, "recognise", ["book a cab to sector 18", "hello", "doctor chahiye"])
    assert [r["ok"] for r in res] == [True, True, True]
    assert res[0]["value"] == {"intent": "book_cab", "slots": {"destination": "Sector 18"}}
    assert res[1]["value"] is None
    crash = await sb.run("def recognise(t):\n    return 1 / 0\n", "recognise", ["x"])
    assert not crash[0]["ok"] and "ZeroDivisionError" in crash[0]["error"]
    blocked = await sb.run("import socket\ndef recognise(t):\n    return None\n", "recognise", ["x"])
    assert "static check" in blocked[0]["error"]


async def test_sandbox_off_never_runs():
    res = await Sandbox(mode="off").run(GOOD, "recognise", ["cab"])
    assert res == [{"ok": False, "error": "sandbox off"}]


def test_normalise_and_agree():
    assert normalise({"intent": "nope"}) is None
    assert normalise(None) is None
    assert normalise({"intent": "book_cab", "slots": {"quantity": "many"}}) is None
    a = normalise({"intent": "book_cab", "slots": {"destination": "Sector 18"}})
    assert a is not None and agrees(a, {"intent": "book_cab", "slots": {"destination": "sector  18"}})
    assert not agrees(a, {"intent": "book_cab", "slots": {}})
    assert strip_code("```python\nx = 1\n```") == "x = 1\n"


class FakeCompiler:
    def __init__(self, *codes: str) -> None:
        self.codes = list(codes)
        self.prompts: list[str] = []

    async def compile_json(self, route: str, system: str, user: str) -> tuple[str, int]:
        self.prompts.append(user)
        return "```python\n" + self.codes.pop(0) + "```", 1000


def _llm_answer(text: str) -> IntentResponse:
    t = text.lower()
    if "cab" in t:
        dest = t.split(" to ", 1)[1].title() if " to " in t else None
        return IntentResponse(
            intent="book_cab", slots=Slots(destination=dest), reply_style="en", reply_text="ok"
        )
    if "doctor" in t:
        return IntentResponse(intent="book_doctor", reply_style="en", reply_text="ok")
    return IntentResponse(intent="chitchat", reply_style="en", reply_text="hi")


UTTERANCES = [
    *(f"book a cab to sector {n}" for n in range(1, 13)),
    *(f"please get a doctor for me {n}" for n in range(8)),
    *(f"how are you {n}" for n in range(6)),
]


async def test_code_skill_lifecycle_learns_then_serves_without_llm(tmp_path):
    skills = CodeSkills(SkillStore(tmp_path / "s.db"), Sandbox(mode="subprocess"), min_samples=20,
                        shadow_agree=3, auto_approve=False)  # fmt: skip
    calls = 0

    async def serve(text: str) -> tuple[IntentResponse, str]:
        nonlocal calls

        async def ask() -> IntentResponse:
            nonlocal calls
            calls += 1
            return _llm_answer(text)

        return await skills.intent(text, "en", ask)

    for u in UTTERANCES:
        assert (await serve(u))[1] == "llm"
    assert skills.due()

    # First attempt answers everything as a cab (bad precision); the feedback names the mistakes; v2 passes.
    llm = FakeCompiler(WRONG, GOOD)
    sid = await skills.compile(llm)
    assert sid is not None and "previous version was rejected" in llm.prompts[1]
    skill = skills.store.get(sid)
    assert skill.status == "tested" and skill.verify()

    from app.skills.engine import SkillEngine

    SkillEngine(skills.store).approve(sid, "family")
    for u in [
        "book a cab to sector 40",
        "book a cab to sector 41",
        "doctor please",
        "book a cab to sector 42",
    ]:
        await serve(u)
        await _drain(skills)
    assert skills.store.get(sid).status == "active"

    calls = 0
    res, handler = await serve("book a cab to sector 77")
    assert handler == f"skill:{sid}" and calls == 0
    assert res.intent == "book_cab" and res.slots.destination == "Sector 77" and res.reply_text
    res, handler = await serve("what's the weather")  # the skill abstains: the LLM answers
    assert handler == "llm" and calls == 1


async def test_shadow_disagreement_degrades(tmp_path):
    skills = CodeSkills(SkillStore(tmp_path / "s.db"), Sandbox(mode="subprocess"), min_samples=1,
                        shadow_agree=3, auto_approve=True)  # fmt: skip
    sid = skills.store.create("recogniser", "intent", 1, "shadow", WRONG, [], 1.0, 0)

    async def ask() -> IntentResponse:
        return _llm_answer("doctor")

    await skills.intent("doctor", "en", ask)
    await _drain(skills)
    assert skills.store.get(sid).status == "degraded"


async def test_tampered_code_is_never_run(tmp_path):
    skills = CodeSkills(SkillStore(tmp_path / "s.db"), Sandbox(mode="subprocess"), min_samples=1)
    sid = skills.store.create("recogniser", "intent", 1, "active", GOOD, [], 1.0, 0)
    skills.store.set(sid, body=WRONG)  # body changed without a new sha256

    async def ask() -> IntentResponse:
        return _llm_answer("doctor")

    assert (await skills.intent("book a cab to x", "en", ask))[1] == "llm"


async def _drain(skills: CodeSkills) -> None:
    import asyncio

    while skills._bg:
        await asyncio.gather(*list(skills._bg))
