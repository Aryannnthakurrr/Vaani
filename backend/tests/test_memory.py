"""Agent memory: facts by topic, what each destination may see, the Meko mirror (MCP calls faked), and the
fallback that hides Meko outages."""

from __future__ import annotations

import asyncio
from typing import Any

from app.memory import AUDIENCES, FallbackMemory, fact_key, format_memories
from app.memory.local import LocalMemory
from app.memory.meko import TASKS, VOICE, MekoMemory, elder_for


async def test_facts_replace_by_topic_and_key_in_any_language(tmp_path):
    mem = LocalMemory(tmp_path / "m.db")
    await mem.remember("dev1", "Home is Sector 18, Noida", "home", fact_key("home", None))
    await mem.remember("dev1", "हम गुड़गाँव आ गए हैं", "home", fact_key("home", None))  # moved: replaces
    await mem.remember("dev1", "Beti Priya Pune mein rehti hai", "family", "daughter")
    await mem.remember("dev1", "बेटी प्रिया अब बंगलौर में है", "family", "daughter")  # replaces, other script
    await mem.remember("dev1", "Beta Ravi Delhi mein hai", "family", "son")
    await mem.remember("dev1", "Prefers Ola over Uber", "preference")
    await mem.remember("dev1", "prefers ola over uber", "preference")  # unkeyed duplicate, ignored
    await mem.remember("dev2", "Someone else's fact", "other")
    facts = await mem.facts("dev1")
    assert facts == [
        ("preference", "Prefers Ola over Uber"),
        ("family", "Beta Ravi Delhi mein hai"),
        ("family", "बेटी प्रिया अब बंगलौर में है"),
        ("home", "हम गुड़गाँव आ गए हैं"),
    ]
    assert await mem.facts("dev1", ["home"]) == [("home", "हम गुड़गाँव आ गए हैं")]


async def test_forget_one_fact_or_a_whole_topic(tmp_path):
    mem = LocalMemory(tmp_path / "m.db")
    for key, text in (("daughter", "Beti Priya Pune mein"), ("son", "Beta Ravi Delhi mein")):
        await mem.remember("dev1", text, "family", key)
    await mem.remember("dev1", "Sugar ki dawai subah", "health", "diabetes")
    assert await mem.forget("dev1", "family", "Daughter ") == 1  # keys are normalised
    assert [t for _, t in await mem.facts("dev1")] == ["Sugar ki dawai subah", "Beta Ravi Delhi mein"]
    assert await mem.forget("dev1", "health") == 1
    assert await mem.facts("dev1") == [("family", "Beta Ravi Delhi mein")]


async def test_old_databases_gain_keys_without_losing_facts(tmp_path):
    import sqlite3

    db = tmp_path / "old.db"
    with sqlite3.connect(db) as c:
        c.execute("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, "
                  "kind TEXT NOT NULL, text TEXT NOT NULL, meta TEXT NOT NULL DEFAULT '{}', ts REAL NOT NULL)")  # fmt: skip
        c.execute("INSERT INTO memories(device_id,kind,text,ts) VALUES ('dev1','fact','Likes tea',1)")
    mem = LocalMemory(db)
    assert await mem.facts("dev1") == [("fact", "Likes tea")]
    await mem.remember("dev1", "Likes coffee now", "preference", "drink")
    assert len(await mem.facts("dev1")) == 2


def test_audiences_keep_health_and_family_off_calls():
    assert AUDIENCES["voice"] >= {"name", "home", "family", "doctor", "health", "preference", "routine"}
    assert AUDIENCES["clinic_call"] == {"name"}  # a call's reason and needs come with the call request
    assert AUDIENCES["family"] == {"name"}


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
        if tool == "memory_add":  # Meko may split one fact into several memories
            return {"results": [{"id": f"m{len(self.calls)}a"}, {"id": f"m{len(self.calls)}b"}]}
        if tool == "memory_search":
            return {"results": [{"memory": "Daughter Priya lives in Pune"}] * 2}
        return {"ok": True}

    def tools(self, name: str) -> list[dict[str, Any]]:
        return [a for t, a in self.calls if t == name]


async def test_meko_gives_each_elder_their_own_datapack(tmp_path):
    local = LocalMemory(tmp_path / "m.db")
    meko = FakeMeko(ids=local)
    await meko.remember("abcdef123456", "Daughter Priya lives in Pune", "family", "daughter")
    assert await meko.recall("abcdef123456", "daughter") == ["Daughter Priya lives in Pune"]  # deduped
    for action in ("tap", "done"):
        step = {"action": {"action": action}, "handler": "llm"}
        await meko.log_step("abcdef123456", "run-1", "book a ride", step)
    await meko.remember("abcdef12-other", "Son Ravi in Delhi", "family", "son")

    mine = f"dp-{elder_for('abcdef123456')}"
    assert elder_for("abcdef123456") != elder_for("abcdef12-other")  # hashed: shared prefixes never collide
    assert [a["name"] for a in meko.tools("datapack_create")] == [
        elder_for(d) for d in ("abcdef123456", "abcdef12-other")
    ]
    first_add, other_add = meko.tools("memory_add")
    assert first_add["datapack_id"] == mine and first_add["agent_id"] == VOICE
    assert other_add["datapack_id"] != mine  # the privacy boundary
    assert meko.tools("memory_search")[0]["datapack_id"] == mine
    assert all(
        a["datapack_id"] == mine and a["agent_id"] == TASKS for a in meko.tools("conversation_add_message")
    )
    assert isinstance(first_add["metadata"], str) and "scope" not in first_add
    # The datapack and profile ids survive a restart through the local store.
    again = FakeMeko(ids=local)
    await again.remember("abcdef123456", "Likes tea", "preference", "drink")
    assert [t for t, _ in again.calls] == ["memory_add"] and again.calls[0][1]["datapack_id"] == mine


async def test_meko_replaces_and_forgets_its_copy_too(tmp_path):
    meko = FakeMeko(ids=LocalMemory(tmp_path / "m.db"))
    await meko.remember("dev-1", "Home is Noida", "home", "home")
    first_ids = {"m4a", "m4b"}  # setup conversation, datapack, profile, then memory_add (call 4)
    await meko.remember("dev-1", "Home is Gurgaon", "home", "home")
    assert {a["memory_id"] for a in meko.tools("memory_delete_by_id")} == first_ids
    await meko.remember("dev-1", "Walks at 6 am", "routine", "walk")
    await meko.forget("dev-1", "home")
    deleted = [a["memory_id"] for a in meko.tools("memory_delete_by_id")]
    assert set(deleted) == {"m4a", "m4b", "m7a", "m7b"}  # both homes gone; the routine (call 8) stays


def test_meko_unwraps_result_strings_and_errors():
    from types import SimpleNamespace as NS

    from app.memory.meko import _payload

    ok = NS(is_error=False, structured_content=None, content=[NS(text='{"result": "{\\"id\\": \\"c1\\"}"}')])
    assert _payload(ok) == {"id": "c1"}


class BrokenMemory(LocalMemory):
    name = "broken"

    async def remember(self, *a: Any, **k: Any) -> None:
        raise ConnectionError

    async def forget(self, *a: Any, **k: Any) -> None:
        raise ConnectionError

    async def log_step(self, *a: Any, **k: Any) -> None:
        raise ConnectionError


async def test_fallback_hides_primary_outage(tmp_path):
    mem = FallbackMemory(BrokenMemory(tmp_path / "b.db"), LocalMemory(tmp_path / "l.db"))
    await mem.remember("dev1", "Takes BP tablets at 9am", "health", "bp")
    await asyncio.gather(*mem._bg)
    assert await mem.facts("dev1") == [("health", "Takes BP tablets at 9am")]
    assert await mem.forget("dev1", "health", "bp") == 1
    await mem.log_step("dev1", "r", "g", {"seq": 0})
    await asyncio.gather(*mem._bg)
    assert await mem.run_state("dev1", "r") == [{"seq": 0}]


class SlowMeko(LocalMemory):
    """Meko as measured live: 5-18 s per call, far past the voice call's 3 s budget."""

    name = "slow"

    async def remember(self, *a: Any, **k: Any) -> None:
        await asyncio.sleep(30)

    async def facts(self, *a: Any, **k: Any) -> list[tuple[str, str]]:
        await asyncio.sleep(30)
        return [("other", "from meko")]


async def test_remember_and_read_never_wait_on_meko(tmp_path):
    mem = FallbackMemory(SlowMeko(tmp_path / "s.db"), LocalMemory(tmp_path / "l.db"))
    await asyncio.wait_for(
        mem.remember("dev1", "Home is Sector 18", "home", "home"), 0.5
    )  # the voice tool waits
    assert await asyncio.wait_for(mem.facts("dev1"), 0.5) == [("home", "Home is Sector 18")]
    assert await asyncio.wait_for(mem.facts("dev2"), 0.5) == []  # a new elder with nothing stored
    for task in list(mem._bg):
        task.cancel()


def test_format_memories_groups_by_topic():
    assert format_memories([]) == ""
    block = format_memories([("family", "Beta Ravi Delhi mein"), ("family", "बेटी प्रिया पुणे में"), ("fact", "x")])
    assert "- family: Beta Ravi Delhi mein | बेटी प्रिया पुणे में" in block
    assert "- other: x" in block  # facts stored before topics existed
