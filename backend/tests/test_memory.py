"""Agent memory: local store, Meko adapter (MCP calls faked), and the fallback that hides Meko outages."""

from __future__ import annotations

import asyncio
from typing import Any

from app.memory import FallbackMemory, format_memories
from app.memory.local import LocalMemory
from app.memory.meko import TASKS, VOICE, MekoMemory, elder_for


async def test_local_remember_recall_and_dedupe(tmp_path):
    mem = LocalMemory(tmp_path / "m.db")
    await mem.remember("dev1", "Daughter Priya lives in Pune", kind="fact")
    await mem.remember("dev1", "daughter priya lives in pune")  # duplicate, ignored
    await mem.remember("dev1", "Prefers Ola over Uber", kind="preference")
    await mem.remember("dev2", "Son Ravi in Delhi")
    got = await mem.recall("dev1", "call my daughter")
    assert got[0] == "Daughter Priya lives in Pune"
    assert "Son Ravi in Delhi" not in await mem.recall(
        "dev1", "son"
    )  # devices never see each other's memories
    assert len(await mem.recall("dev1", "zzz unrelated")) == 2  # falls back to recent stable facts
    assert (await mem.health())["memories"] == 3


async def test_local_run_state_checkpoints(tmp_path):
    mem = LocalMemory(tmp_path / "m.db")
    for i in range(3):
        await mem.log_step("dev1", "run-1", "book a ride", {"seq": i, "action": {"action": "tap"}})
    state = await mem.run_state("dev1", "run-1")
    assert [s["seq"] for s in state] == [0, 1, 2]
    assert await mem.run_state("other", "run-1") == []


class FakeMeko(MekoMemory):
    def __init__(self, ids=None) -> None:
        super().__init__("mko_tkn_test", "https://example.invalid/mcp", ids=ids)
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def _call(self, tool: str, args: dict[str, Any]) -> Any:
        self.calls.append((tool, args))
        if tool == "conversation_create":
            return {"id": f"conv-{len(self.calls)}"}
        if tool == "datapack_create":
            return {"datapack_id": f"dp-{args['name']}", "name": args["name"]}
        if tool == "memory_search":
            return {"results": [{"memory": "Daughter Priya lives in Pune"}] * 2}
        return {"ok": True}


async def test_meko_gives_each_elder_their_own_datapack(tmp_path):
    local = LocalMemory(tmp_path / "m.db")
    meko = FakeMeko(ids=local)
    await meko.remember("abcdef123456", "Daughter Priya lives in Pune")
    assert await meko.recall("abcdef123456", "daughter") == ["Daughter Priya lives in Pune"]  # deduped
    for action in ("tap", "done"):
        step = {"action": {"action": action}, "handler": "llm"}
        await meko.log_step("abcdef123456", "run-1", "book a ride", step)
    await meko.remember("abcdef12-other", "Son Ravi in Delhi")

    mine = f"dp-{elder_for('abcdef123456')}"
    assert elder_for("abcdef123456") != elder_for(
        "abcdef12-other"
    )  # hashed, so shared prefixes never collide
    by_tool: dict[str, list[dict[str, Any]]] = {}
    for tool, args in meko.calls:
        by_tool.setdefault(tool, []).append(args)
    assert [a["name"] for a in by_tool["datapack_create"]] == [
        elder_for("abcdef123456"),
        elder_for("abcdef12-other"),
    ]
    first_add, other_add = by_tool["memory_add"]
    assert first_add["datapack_id"] == mine and first_add["agent_id"] == VOICE
    assert other_add["datapack_id"] != mine  # the privacy boundary
    assert by_tool["memory_search"][0]["datapack_id"] == mine
    assert all(
        a["datapack_id"] == mine and a["agent_id"] == TASKS for a in by_tool["conversation_add_message"]
    )
    assert isinstance(first_add["metadata"], str) and "scope" not in first_add
    # The datapack and profile ids survive a restart through the local store.
    again = FakeMeko(ids=local)
    await again.remember("abcdef123456", "Likes tea")
    assert [t for t, _ in again.calls] == ["memory_add"] and again.calls[0][1]["datapack_id"] == mine


def test_meko_unwraps_result_strings_and_errors():
    from types import SimpleNamespace as NS

    from app.memory.meko import _payload

    ok = NS(is_error=False, structured_content=None, content=[NS(text='{"result": "{\\"id\\": \\"c1\\"}"}')])
    assert _payload(ok) == {"id": "c1"}


class BrokenMemory(LocalMemory):
    name = "broken"

    async def remember(self, *a: Any, **k: Any) -> None:
        raise ConnectionError

    async def recall(self, *a: Any, **k: Any) -> list[str]:
        raise TimeoutError

    async def log_step(self, *a: Any, **k: Any) -> None:
        raise ConnectionError


async def test_fallback_hides_primary_outage(tmp_path):
    mem = FallbackMemory(BrokenMemory(tmp_path / "b.db"), LocalMemory(tmp_path / "l.db"))
    await mem.remember("dev1", "Takes BP tablets at 9am")
    await asyncio.gather(*mem._bg)
    assert await mem.recall("dev1", "tablets") == ["Takes BP tablets at 9am"]
    await mem.log_step("dev1", "r", "g", {"seq": 0})
    await asyncio.gather(*mem._bg)
    assert await mem.run_state("dev1", "r") == [{"seq": 0}]


class SlowMeko(LocalMemory):
    """Meko as measured live: 5-18 s per call, far past the voice call's 3 s budget."""

    name = "slow"

    async def remember(self, *a: Any, **k: Any) -> None:
        await asyncio.sleep(30)

    async def recall(self, *a: Any, **k: Any) -> list[str]:
        await asyncio.sleep(30)
        return ["from meko"]


async def test_remember_and_recall_never_wait_on_meko(tmp_path):
    mem = FallbackMemory(SlowMeko(tmp_path / "s.db"), LocalMemory(tmp_path / "l.db"))
    await asyncio.wait_for(mem.remember("dev1", "Home is Sector 18"), 0.5)  # the voice tool waits on this
    assert await asyncio.wait_for(mem.recall("dev1", "home"), 0.5) == ["Home is Sector 18"]
    assert await asyncio.wait_for(mem.recall("dev2", "home"), 0.5) == []  # a new elder with nothing stored
    for task in list(mem._bg):
        task.cancel()


def test_format_memories():
    assert format_memories([]) == ""
    assert "- likes tea" in format_memories(["likes tea"])
