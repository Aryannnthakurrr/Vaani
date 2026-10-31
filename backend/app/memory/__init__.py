"""Agent memory and state (docs/LEARNING.md §3): Meko (Yugabyte) over MCP, with a local SQLite fallback.

Everything that calls memory goes through `get_memory()`; failures never break a user request (memory is an
enhancement), they are logged and the local store is used instead.
"""

from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import Coroutine
from functools import lru_cache
from typing import Any, Protocol

from app.config import get_settings

log = logging.getLogger("saathi.memory")


class Memory(Protocol):
    name: str

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None: ...

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]: ...

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None: ...

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]: ...

    async def health(self) -> dict[str, Any]: ...


class FallbackMemory:
    """Local answers every read; Meko is the durable, per-elder copy, written in the background.

    Meko writes take 5-16 s and a search 15-18 s (measured 2026-10-31), while the voice call gives recall 3 s and
    the `remember` tool's answer holds up the conversation. So nobody waits on Meko: writes land locally at once
    and reach Meko later, and reads come from the local copy, which holds every fact because it is written first.
    Meko reads are safe now that each elder has their own datapack (see meko.py); only their speed keeps them off
    the request path.
    """

    def __init__(self, primary: Memory, local: Memory) -> None:
        self.primary, self.local = primary, local
        self.name = f"{primary.name}+{local.name}"
        self._bg: set[asyncio.Task[Any]] = set()

    def _later(self, what: str, coro: Coroutine[Any, Any, Any]) -> None:
        async def guarded() -> None:
            try:
                await coro
            except Exception as exc:  # noqa: BLE001
                log.warning("meko %s failed: %s", what, type(exc).__name__)

        task = asyncio.create_task(guarded())
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None:
        await self.local.remember(device, text, kind, **meta)
        self._later("remember", self.primary.remember(device, text, kind, **meta))

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        return await self.local.recall(device, query, limit)  # Meko is too slow here: see the class docstring

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        await self.local.log_step(device, run_id, goal, step)
        self._later("log_step", self.primary.log_step(device, run_id, goal, step))

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        return await self.local.run_state(device, run_id)

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


def format_memories(items: list[str]) -> str:
    """Prompt block for what Saathi knows about the user (empty string when nothing)."""
    if not items:
        return ""
    return "What you know about this user (use it; never read it out unprompted):\n" + "\n".join(
        f"- {m}" for m in items
    )
