"""Agent memory and state (docs/LEARNING.md §3): facts about each elder, and where each kind of fact may go.

Facts are stored as the elder said them, in English, Hindi or Hinglish; `topic` and `key` are English labels so
storage, replacement and the sharing rules below work the same in every language. Everything goes through
`get_memory()`; failures never break a user request (memory is an enhancement), they are logged instead.
"""

from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import Coroutine, Iterable
from functools import lru_cache
from typing import Any, Literal, Protocol

from app.config import get_settings

log = logging.getLogger("saathi.memory")

Topic = Literal["name", "home", "family", "doctor", "health", "preference", "routine", "other"]
TOPICS: tuple[str, ...] = ("name", "home", "family", "doctor", "health", "preference", "routine", "other")
SINGLE = {"name", "home"}  # one value per elder: a new one always replaces the old

# Which facts each destination may receive. Anything not listed never leaves for that destination.
# - voice: the elder's own assistant, so everything they told it.
# - clinic_call: only how to address them. The visit's reason and needed facilities come with each call request
#   (said for that call, never pulled from stored health facts), and nothing else of their history is shared.
# - family: their name, for alerts. /intent and /agent/step get no facts at all: they produce the training data
#   for compiled skills, and varying prompt text would make the same request map to different answers.
AUDIENCES: dict[str, frozenset[str]] = {
    "voice": frozenset(TOPICS),
    "clinic_call": frozenset({"name"}),
    "family": frozenset({"name"}),
}


# The parts of Saathi that learn things, each its own Meko agent, so every fact says who learnt it. With
# `learned_from` (where: "booking call to Fortis Memorial", "told Saathi by voice") in each fact's metadata.
AGENTS: dict[str, str] = {
    "saathi-voice": "the voice assistant, from what the user said",
    "saathi-care": "the doctor feature, from hospital calls and bookings",
    "saathi-tasks": "the phone agent, from tasks it did in apps",
    "saathi-inbox": "the inbox, from bills and messages (summaries only, never message text)",
    "saathi-camera": "the shopping camera",
    "saathi-abilities": "compiled abilities, their running state",
    "saathi-family": "the family's setup",
}


def fact_key(topic: str, key: str | None) -> str:
    """Single-valued topics key on themselves, so a second home or name replaces the first."""
    return (key or "").strip() or (topic if topic in SINGLE else "")


class Memory(Protocol):
    name: str

    async def remember(
        self, device: str, text: str, kind: str = "other", key: str = "", **meta: Any
    ) -> None: ...

    async def forget(self, device: str, kind: str, key: str = "") -> Any: ...

    async def facts(self, device: str, topics: Iterable[str] | None = None) -> list[tuple[str, str]]: ...

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None: ...

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]: ...

    async def health(self) -> dict[str, Any]: ...


class FallbackMemory:
    """Local answers every read; Meko is the durable, per-elder copy, written in the background.

    Meko writes take 5-16 s and a search 15-18 s (measured 2026-10-31), while the voice call reads facts as it
    starts and the `remember` tool's answer holds up the conversation. So nobody waits on Meko: writes and deletes
    land locally at once and reach Meko later, and reads come from the local copy, which holds every fact because
    it is written first. Meko reads are safe (each elder has their own datapack, see meko.py); only their speed
    keeps them off the request path.
    """

    def __init__(self, primary: Any, local: Memory) -> None:
        self.primary, self.local = primary, local
        self.name = f"{primary.name}+{local.name}"
        self._bg: set[asyncio.Task[Any]] = set()
        self._stored: dict[str, set[str]] = {}

    def _later(self, what: str, coro: Coroutine[Any, Any, Any]) -> None:
        async def guarded() -> None:
            try:
                await coro
            except Exception as exc:  # noqa: BLE001
                log.warning("meko %s failed: %s", what, type(exc).__name__)

        task = asyncio.create_task(guarded())
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    async def remember(self, device: str, text: str, kind: str = "other", key: str = "", **meta: Any) -> None:
        await self.local.remember(device, text, kind, key, **meta)
        self._later("remember", self.primary.remember(device, text, kind, key, **dict(meta)))

    async def entries(self, device: str) -> list[dict[str, Any]]:
        return await self.local.entries(device)

    async def forget(self, device: str, kind: str, key: str = "") -> int:
        n = await self.local.forget(device, kind, key)
        self._later("forget", self.primary.forget(device, kind, key))
        return n

    async def facts(self, device: str, topics: Iterable[str] | None = None) -> list[tuple[str, str]]:
        return await self.local.facts(device, topics)  # Meko is too slow here: see the class docstring

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        await self.local.log_step(device, run_id, goal, step)
        self._later("log_step", self.primary.log_step(device, run_id, goal, step))

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        return await self.local.run_state(device, run_id)

    async def seed(self, device: str, text: str, kind: str, key: str = "", **meta: Any) -> None:
        """A demo fact: stored locally now; sent to Meko only if no backend has put it in that datapack yet."""
        await self.local.remember(device, text, kind, key, **meta)

        async def once() -> None:
            if device not in self._stored:
                self._stored[device] = await self.primary.stored_texts(device)
            if text.strip().lower() not in self._stored[device]:
                await self.primary.remember(device, text, kind, key, **dict(meta))
                self._stored[device].add(text.strip().lower())

        self._later("seed", once())

    def mirror_state(self, device: str, name: str, state: dict[str, Any]) -> None:
        """A compiled ability's new state to Meko; the local copy is app/abilities' own table."""
        self._later("save_state", self.primary.save_state(device, name, state))

    async def health(self) -> dict[str, Any]:
        return {"backend": self.name, "meko": await self.primary.health(), "local": await self.local.health()}


@lru_cache
def get_memory() -> Memory:
    from app.memory.local import LocalMemory

    settings = get_settings()
    local = LocalMemory(settings.database_path)
    key = os.environ.get("MEKO_API_KEY", "").strip()
    if not key:
        log.info("MEKO_API_KEY not set: memory and agent state stay in the local database")
        return local
    from app.memory.meko import MekoMemory

    meko = MekoMemory(
        api_key=key,
        url=os.environ.get("MEKO_URL", "https://mcp.mekodata.ai/mcp").strip(),
        ids=local,
    )
    return FallbackMemory(meko, local)


async def facts_for(device: str, audience: str) -> list[tuple[str, str]]:
    """The elder's facts that this destination is allowed to see (see AUDIENCES)."""
    return await get_memory().facts(device, AUDIENCES[audience])


PLACEHOLDER_NAMES = {"", "the patient", "saathi user"}


async def name_for(device: str, given: str | None = None) -> str | None:
    """How to address the elder: what the app sent, else what they told Saathi, else the family's setup name."""
    if given and given.strip().lower() not in PLACEHOLDER_NAMES:
        return given.strip()
    named = await get_memory().facts(device, ["name"])
    if named:
        return named[0][1]
    from app.deps import get_db

    stored = get_db().get_device(device)
    if stored and stored.user_name.strip().lower() not in PLACEHOLDER_NAMES:
        return stored.user_name.strip()
    return None


def format_memories(items: list[tuple[str, str]]) -> str:
    """Prompt block for what Saathi knows about the user, grouped by topic (empty string when nothing)."""
    if not items:
        return ""
    by_topic: dict[str, list[str]] = {}
    for topic, text in items:
        by_topic.setdefault(topic if topic in TOPICS else "other", []).append(text)
    lines = [f"- {topic}: " + " | ".join(texts) for topic, texts in by_topic.items()]
    return "What you know about this user (use it; never read it out unprompted):\n" + "\n".join(lines)
