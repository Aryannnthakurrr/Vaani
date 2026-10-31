"""Agent memory: local store, Meko adapter (MCP calls faked), and the fallback that hides Meko outages."""

from __future__ import annotations

from typing import Any

from app.memory import FallbackMemory, format_memories
from app.memory.local import LocalMemory
from app.memory.meko import MekoMemory, agent_for


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
    def __init__(self) -> None:
        super().__init__("mko_tkn_test", "https://example.invalid/mcp", "saathi")
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def _call(self, tool: str, args: dict[str, Any]) -> Any:
        self.calls.append((tool, args))
        if tool == "datapack_list":
            return {"datapacks": []}
        if tool == "datapack_create":
            return {"datapack": {"id": "dp-1", "name": args["name"]}}
        if tool == "memory_search":
            return {"results": [{"memory": "Daughter Priya lives in Pune"}]}
        if tool == "conversation_create":
            return {"conversation_id": "conv-9"}
        return {"ok": True}


async def test_meko_maps_calls_and_reuses_datapack_and_conversation():
    meko = FakeMeko()
    await meko.remember("abcdef123456", "Daughter Priya lives in Pune")
    assert await meko.recall("abcdef123456", "daughter") == ["Daughter Priya lives in Pune"]
    await meko.log_step(
        "abcdef123456", "run-1", "book a ride", {"action": {"action": "tap"}, "handler": "llm"}
    )
    await meko.log_step(
        "abcdef123456", "run-1", "book a ride", {"action": {"action": "done"}, "handler": "llm"}
    )
    tools = [t for t, _ in meko.calls]
    assert tools.count("datapack_create") == 1 and tools.count("conversation_create") == 1
    assert tools.count("conversation_add_message") == 2
    add = dict(meko.calls)["memory_add"]
    assert add["agent_id"] == agent_for("abcdef123456") == "elder-abcdef12"
    assert add["datapack_id"] == "dp-1" and "scope" not in add


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
    assert await mem.recall("dev1", "tablets") == ["Takes BP tablets at 9am"]
    await mem.log_step("dev1", "r", "g", {"seq": 0})
    assert await mem.run_state("dev1", "r") == [{"seq": 0}]


def test_format_memories():
    assert format_memories([]) == ""
    assert "- likes tea" in format_memories(["likes tea"])
