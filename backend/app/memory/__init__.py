"""Agent memory and state (docs/LEARNING.md §3): Meko (Yugabyte) over MCP, with a local SQLite fallback.

Everything that calls memory goes through `get_memory()`; failures never break a user request (memory is an
enhancement), they are logged and the local store is used instead.
"""

from __future__ import annotations

import logging
import os
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
    """Meko first; the local store on any error, so a memory outage is invisible to the elder."""

    def __init__(self, primary: Memory, local: Memory) -> None:
        self.primary, self.local = primary, local
        self.name = f"{primary.name}+{local.name}"

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None:
        await self.local.remember(device, text, kind, **meta)  # always keep a local copy
        try:
            await self.primary.remember(device, text, kind, **meta)
        except Exception as exc:  # noqa: BLE001
            log.warning("meko remember failed: %s", type(exc).__name__)

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        try:
            got = await self.primary.recall(device, query, limit)
            if got:
                return got
        except Exception as exc:  # noqa: BLE001
            log.warning("meko recall failed: %s", type(exc).__name__)
        return await self.local.recall(device, query, limit)

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        await self.local.log_step(device, run_id, goal, step)
        try:
            await self.primary.log_step(device, run_id, goal, step)
        except Exception as exc:  # noqa: BLE001
            log.warning("meko log_step failed: %s", type(exc).__name__)

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        return await self.local.run_state(device, run_id)

    async def health(self) -> dict[str, Any]:
        try:
            primary = await self.primary.health()
        except Exception as exc:  # noqa: BLE001
            primary = {"ok": False, "error": type(exc).__name__}
        return {"backend": self.name, "meko": primary, "local": await self.local.health()}


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
        datapack=os.environ.get("MEKO_DATAPACK", "saathi").strip(),
    )
    return FallbackMemory(meko, local)


def format_memories(items: list[str]) -> str:
    """Prompt block for what Saathi knows about the user (empty string when nothing)."""
    if not items:
        return ""
    return "What you know about this user (use it; never read it out unprompted):\n" + "\n".join(
        f"- {m}" for m in items
    )
