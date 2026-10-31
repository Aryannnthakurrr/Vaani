"""Meko (Yugabyte) over MCP: per-elder memories, agent run traces (conversations), and token usage.

Endpoint `https://mcp.mekodata.ai/mcp`, `Authorization: Bearer mko_tkn_…` plus a User-Agent (Meko rejects requests
without one). Each call opens a short MCP session: memory traffic is a few calls per task, so simplicity wins over a
pooled connection. Elders are separated by `agent_id = elder-<device8>` inside one datapack.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

log = logging.getLogger("saathi.memory.meko")
TIMEOUT_S = 8.0


def agent_for(device: str) -> str:
    return f"elder-{device[:8]}"


class MekoMemory:
    name = "meko"

    def __init__(self, api_key: str, url: str, datapack: str) -> None:
        self.api_key, self.url, self.datapack_name = api_key, url, datapack
        self._datapack_id: str | None = None
        self._conversations: dict[str, str] = {}  # run_id -> conversation_id
        self._lock = asyncio.Lock()

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
        if getattr(result, "isError", False):
            raise RuntimeError(f"meko {tool} error: {_text(result)[:200]}")
        return _payload(result)

    async def datapack(self) -> str:
        if self._datapack_id:
            return self._datapack_id
        async with self._lock:
            if self._datapack_id:
                return self._datapack_id
            found = await self._call("datapack_list", {"query": self.datapack_name, "limit": 20})
            for dp in _items(found, "datapacks"):
                if dp.get("name") == self.datapack_name:
                    self._datapack_id = str(dp.get("id") or dp.get("datapack_id"))
                    break
            if not self._datapack_id:
                made = await self._call("datapack_create", {"name": self.datapack_name})
                self._datapack_id = str(_get(made, "id", "datapack_id"))
                log.info("created Meko datapack %s", self.datapack_name)
            return self._datapack_id

    async def remember(self, device: str, text: str, kind: str = "fact", **meta: Any) -> None:
        await self._call("memory_add", {
            "text": text, "agent_id": agent_for(device), "datapack_id": await self.datapack(),
            "metadata": {"kind": kind, **{k: str(v) for k, v in meta.items()}},
        })  # fmt: skip

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        found = await self._call("memory_search", {
            "query": query, "agent_id": agent_for(device), "datapack_id": await self.datapack(), "limit": limit,
        })  # fmt: skip
        out: list[str] = []
        for m in _items(found, "memories", "results"):
            text = m.get("memory") or m.get("text") or m.get("content")
            if text:
                out.append(str(text))
        return out[:limit]

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        """One Meko conversation per agent run; each step is a message with the action and the reasoning."""
        dp = await self.datapack()
        conv = self._conversations.get(run_id)
        if conv is None:
            made = await self._call("conversation_create", {
                "agent_id": agent_for(device), "datapack_id": dp, "title": goal[:80], "run_id": run_id,
                "metadata": {"task": str(step.get("task", "")), "kind": "agent_run"},
            })  # fmt: skip
            conv = str(_get(made, "id", "conversation_id"))
            self._conversations[run_id] = conv
        await self._call("conversation_add_message", {
            "conversation_id": conv, "agent_id": agent_for(device), "datapack_id": dp,
            "input": f"screen ({step.get('screen_chars', 0)} chars, {step.get('package', '')})",
            "output": json.dumps(step.get("action", {}), ensure_ascii=False),
            "reasoning": str(step.get("reason", "")), "plan": str(step.get("handler", "")),
            "metadata": {"seq": str(step.get("seq", "")), "handler": str(step.get("handler", ""))},
        })  # fmt: skip

    async def run_state(self, device: str, run_id: str) -> list[dict[str, Any]]:
        conv = self._conversations.get(run_id)
        if not conv:
            return []
        got = await self._call("conversation_get", {
            "conversation_id": conv, "agent_id": agent_for(device), "include_messages": True,
            "datapack_id": await self.datapack(),
        })  # fmt: skip
        return list(_items(got, "messages"))

    async def health(self) -> dict[str, Any]:
        return {"ok": True, "datapack": await self.datapack()}


# --------------------------------------------------------------------------- result helpers


def _text(result: Any) -> str:
    return "".join(getattr(c, "text", "") or "" for c in getattr(result, "content", []) or [])


def _payload(result: Any) -> Any:
    structured = getattr(result, "structuredContent", None)
    if structured:
        return structured
    raw = _text(result)
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
