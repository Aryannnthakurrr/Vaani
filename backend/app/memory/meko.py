"""Meko (Yugabyte) over MCP: per-elder memories and agent run traces (conversations).

Endpoint `https://mcp.mekodata.ai/mcp`, `Authorization: Bearer mko_tkn_…` plus a User-Agent (Meko rejects requests
without one). Verified against the live service on 2026-10-31:
- memories are scoped to a conversation, so each elder has one long-lived "profile" conversation, created once and
  its id kept in the local database (`ids`), so it survives restarts;
- `metadata` must be a JSON string; a conversation only accepts messages from the agent_id that created it;
- tool errors come back as `{"result": "{\"error\": …}"}` text rather than MCP errors;
- each call takes 5-16 s, so callers never wait on Meko (see `FallbackMemory`).
Datapacks are left to Meko's default (its datapack tools also require a conversation).
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from typing import Any

log = logging.getLogger("saathi.memory.meko")
TIMEOUT_S = 30.0


def agent_for(device: str) -> str:
    """A stable, non-reversible id per phone (prefixes of device ids can collide)."""
    return "elder-" + hashlib.sha256(device.encode()).hexdigest()[:16]


class MekoMemory:
    name = "meko"

    def __init__(self, api_key: str, url: str, ids: Any = None) -> None:
        self.api_key, self.url = api_key, url
        self.ids = ids  # persistent key → Meko id map (LocalMemory)
        self._cache: dict[str, str] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def _call(self, tool: str, args: dict[str, Any]) -> Any:
        from mcp import ClientSession
        from mcp.client.streamable_http import streamable_http_client
        from mcp.shared._httpx_utils import create_mcp_http_client

        headers = {"Authorization": f"Bearer {self.api_key}", "User-Agent": "saathi-backend/0.2"}
        async with asyncio.timeout(TIMEOUT_S):
            http = create_mcp_http_client(headers=headers)
            async with http, streamable_http_client(self.url, http_client=http) as (read, write):
                async with ClientSession(read, write) as session:
                    await session.initialize()
                    result = await session.call_tool(tool, args)
        if getattr(result, "is_error", False) or getattr(result, "isError", False):  # mcp 2.x uses snake_case
            raise RuntimeError(f"meko {tool} error: {_text(result)[:200]}")
        payload = _payload(result)
        if isinstance(payload, dict) and payload.get("error"):
            raise RuntimeError(f"meko {tool} error: {payload['error']}")
        return payload

    async def _conversation(self, key: str, device: str, title: str, meta: dict[str, str]) -> str:
        """Create a conversation once per key (profile or run) and remember its id."""
        if key in self._cache:
            return self._cache[key]
        lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            cached = self._cache.get(key) or (self.ids.get_id(key) if self.ids else None)
            if not cached:
                made = await self._call("conversation_create", {
                    "agent_id": agent_for(device), "title": title[:80], "metadata": json.dumps(meta),
                })  # fmt: skip
                cached = str(_get(made, "id", "conversation_id"))
                if self.ids:
                    self.ids.set_id(key, cached)
            self._cache[key] = cached
            return cached

    async def profile(self, device: str) -> str:
        return await self._conversation(f"meko:profile:{device}", device, "profile", {"kind": "profile"})

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None:
        await self._call("memory_add", {
            "conversation_id": await self.profile(device), "text": text, "agent_id": agent_for(device),
            "metadata": json.dumps({"kind": kind, **{k: str(v) for k, v in meta.items()}}),
        })  # fmt: skip

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        found = await self._call("memory_search", {
            "conversation_id": await self.profile(device), "query": query, "agent_id": agent_for(device),
            "limit": limit,
        })  # fmt: skip
        out: list[str] = []
        for m in _items(found, "memories", "results"):
            text = m.get("memory") or m.get("text") or m.get("content")
            if text:
                out.append(str(text))
        return out[:limit]

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        """One Meko conversation per agent run; each step is a message with the action and the reasoning."""
        conv = await self._conversation(f"meko:run:{run_id}", device, goal,
                                        {"kind": "agent_run", "task": str(step.get("task", ""))})  # fmt: skip
        await self._call("conversation_add_message", {
            "conversation_id": conv, "agent_id": agent_for(device),
            "input": f"screen ({step.get('screen_chars', 0)} chars, {step.get('package', '')})",
            "output": json.dumps(step.get("action", {}), ensure_ascii=False),
            "reasoning": str(step.get("reason", "")), "plan": [str(step.get("handler", ""))],
            "metadata": json.dumps({"seq": str(step.get("seq", "")), "handler": str(step.get("handler", ""))}),
        })  # fmt: skip

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        conv = self._cache.get(f"meko:run:{run_id}")
        if not conv:
            return []
        got = await self._call("conversation_get", {
            "conversation_id": conv, "agent_id": agent_for(device), "include_messages": True,
        })  # fmt: skip
        return list(_items(got, "messages"))

    async def health(self) -> dict[str, Any]:
        return {"ok": True}


# --------------------------------------------------------------------------- result helpers


def _text(result: Any) -> str:
    return "".join(getattr(c, "text", "") or "" for c in getattr(result, "content", []) or [])


def _payload(result: Any) -> Any:
    """Meko wraps its JSON answer as a string under "result"; unwrap it."""
    structured = getattr(result, "structured_content", None) or getattr(result, "structuredContent", None)
    payload: Any = structured if structured else _loads(_text(result))
    if isinstance(payload, dict) and isinstance(payload.get("result"), str):
        payload = _loads(payload["result"])
    return payload


def _loads(raw: str) -> Any:
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return {"text": raw}


def _items(payload: Any, *keys: str) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [p for p in payload if isinstance(p, dict)]
    if isinstance(payload, dict):
        for k in (*keys, "items", "data", "results"):
            v = payload.get(k)
            if isinstance(v, list):
                return [p for p in v if isinstance(p, dict)]
            if isinstance(v, dict):
                return _items(v, *keys)
    return []


def _get(payload: Any, *keys: str) -> Any:
    if isinstance(payload, dict):
        for k in keys:
            if payload.get(k):
                return payload[k]
        for v in payload.values():
            if isinstance(v, dict):
                got = _get(v, *keys)
                if got:
                    return got
    raise RuntimeError(f"meko response missing {keys}")
