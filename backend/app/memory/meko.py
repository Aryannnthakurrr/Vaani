"""Meko (Yugabyte) over MCP: per-elder memories and agent run traces (conversations).

Endpoint `https://mcp.mekodata.ai/mcp`, `Authorization: Bearer mko_tkn_…` plus a User-Agent (Meko rejects requests
without one). Verified against the live service on 2026-10-31:
- **The datapack is the privacy boundary.** `memory_search` returns everything in a datapack, whatever
  `agent_id` / `conversation_id` it is given, so each elder gets their own datapack. Inside it, `agent_id` names
  the part of Saathi that wrote the data (voice facts, task runs), and those all share one context. Tested with
  two fresh elders: in a shared datapack each saw the other's fact; with one datapack each, neither did.
- Creating a datapack needs an existing conversation, so a one-off "setup" conversation comes first.
- Memories hang off a conversation, so each elder has one long-lived voice "profile" conversation. The datapack and
  profile ids are kept in the local database (`ids`), so they survive restarts.
- `metadata` must be a JSON string; a conversation only accepts messages from the agent_id that created it;
- tool errors come back as `{"result": "{\"error\": …}"}` text rather than MCP errors;
- each call takes 5-16 s, so callers never wait on Meko (see `FallbackMemory`).
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from app.memory.local import norm_key

log = logging.getLogger("saathi.memory.meko")
TIMEOUT_S = 30.0
MAX_PARALLEL = 2  # Meko rate-limits a token that fires many calls at once (PAT_RATE_LIMITED, measured 2026-11-01)
RATE_LIMIT_WAITS_S = (5.0, 20.0)


def elder_for(device: str) -> str:
    """A stable, non-reversible id per phone, used as the elder's datapack name (prefixes of device ids collide).
    Demo personas ("persona:ramesh") get a readable name instead, the same from every backend."""
    if device.startswith("persona:"):
        return "saathi-persona-" + device.removeprefix("persona:")
    return "elder-" + hashlib.sha256(device.encode()).hexdigest()[:16]


# Parts of Saathi that write into an elder's datapack. They share that elder's context, nobody else's.
SETUP, VOICE, TASKS, ABILITIES = "saathi-setup", "saathi-voice", "saathi-tasks", "saathi-abilities"


class MekoMemory:
    name = "meko"

    def __init__(self, api_key: str, url: str, ids: Any = None) -> None:
        self.api_key, self.url = api_key, url
        self.ids = ids  # persistent key → Meko id map (LocalMemory)
        self._cache: dict[str, str] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._gate = asyncio.Semaphore(MAX_PARALLEL)

    async def _call(self, tool: str, args: dict[str, Any]) -> Any:
        """One Meko tool call; at most MAX_PARALLEL at a time, and a rate-limited call waits and tries again."""
        async with self._gate:
            for wait in (*RATE_LIMIT_WAITS_S, None):
                try:
                    return await self._call_once(tool, args)
                except RuntimeError as exc:
                    if wait is None or "RATE_LIMIT" not in str(exc):
                        raise
                    log.info("meko %s rate-limited, retrying in %.0f s", tool, wait)
                    await asyncio.sleep(wait)
        raise RuntimeError("unreachable")

    async def _call_once(self, tool: str, args: dict[str, Any]) -> Any:
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

    async def _once(self, key: str, make: Callable[[], Awaitable[str]]) -> str:
        """Create a Meko object once per key (datapack, profile, run) and remember its id."""
        if key in self._cache:
            return self._cache[key]
        lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            cached = self._cache.get(key) or (self.ids.get_id(key) if self.ids else None)
            if not cached:
                cached = await make()
                if self.ids:
                    self.ids.set_id(key, cached)
            self._cache[key] = cached
            return cached

    async def _new_conversation(
        self, agent: str, title: str, meta: dict[str, str], datapack: str | None
    ) -> str:
        args = {"agent_id": agent, "title": title[:80], "metadata": json.dumps(meta)}
        made = await self._call(
            "conversation_create", {**args, **({"datapack_id": datapack} if datapack else {})}
        )
        return str(_get(made, "conversation_id", "id"))

    async def datapack(self, device: str) -> str:
        """The elder's own datapack: everything Vaani stores about them, and nothing about anyone else."""

        async def make() -> str:
            name = elder_for(device)
            setup = await self._new_conversation(SETUP, "setup", {"kind": "setup"}, None)
            # A persona's datapack may already exist, made by another backend on the same Meko account (or an earlier
            # run): reuse it. The free tier caps how many datapacks an account has, so never make a second one.
            if device.startswith("persona:"):
                listed = await self._call("datapack_list", {"conversation_id": setup, "query": name, "limit": 50})
                found = next((d for d in _items(listed, "datapacks")
                              if (d.get("datapack_name") or d.get("name")) == name), None)  # fmt: skip
                if found:
                    log.info("using Meko datapack %s", name)
                    return str(_get(found, "datapack_id", "id"))
            made = await self._call("datapack_create", {"name": name, "conversation_id": setup})
            log.info("using Meko datapack %s", name if device.startswith("persona:") else "for an elder")
            return str(_get(made, "datapack_id", "id"))

        return await self._once(f"meko:datapack:{device}", make)

    async def profile(self, device: str, agent: str = VOICE) -> str:
        """An agent's long-lived conversation in the elder's datapack, which the facts it learnt hang off. Each part
        of Saathi writes as its own agent, so Meko records who learnt what (see AGENTS in app.memory)."""
        datapack = await self.datapack(device)
        key = f"meko:voice:{device}" if agent == VOICE else f"meko:profile:{agent}:{device}"
        return await self._once(
            key,
            lambda: self._new_conversation(agent, "profile", {"kind": "profile", "agent": agent}, datapack),
        )

    @staticmethod
    def _bucket(device: str, kind: str, key: str = "") -> str:
        """Local id-map prefix for the Meko memories behind one fact (or, without a key, a whole topic)."""
        return f"meko:mem:{device}:{kind}:" + (f"{key}:" if key else "")

    async def _delete(self, device: str, prefix: str) -> None:
        if not self.ids:
            return
        for stored in self.ids.take_ids(prefix):
            agent, _, memory_id = stored.rpartition("|")  # "agent|id"; older entries are bare voice ids
            agent = agent or VOICE
            await self._call("memory_delete_by_id", {
                "memory_id": memory_id, "datapack_id": await self.datapack(device),
                "conversation_id": await self.profile(device, agent), "agent_id": agent,
            })  # fmt: skip

    async def remember(self, device: str, text: str, kind: str = "other", key: str = "", **meta: Any) -> None:
        """Mirror a fact as the agent that learnt it (`meta["agent"]`, default voice), with where it was learnt in the
        metadata. A keyed fact replaces the earlier copy, as it does locally."""
        key = norm_key(key)
        agent = str(meta.pop("agent", "") or VOICE)
        if key:
            await self._delete(device, self._bucket(device, kind, key))
        added = await self._call("memory_add", {
            "datapack_id": await self.datapack(device), "conversation_id": await self.profile(device, agent),
            "agent_id": agent, "text": text,
            "metadata": json.dumps({"kind": kind, "key": key, "agent": agent,
                                    **{k: str(v) for k, v in meta.items()}}, ensure_ascii=False),
        })  # fmt: skip
        if (
            self.ids
        ):  # Meko may split one fact into several memories; keep every id so forget removes them all
            for hit in _items(added, "results", "memories"):
                if hit.get("id"):
                    self.ids.set_id(
                        self._bucket(device, kind, key or "_") + str(hit["id"]), f"{agent}|{hit['id']}"
                    )

    async def forget(self, device: str, kind: str, key: str = "") -> None:
        key = norm_key(key)
        await self._delete(device, self._bucket(device, kind, key))

    async def recall(self, device: str, query: str, limit: int = 5) -> list[str]:
        found = await self._call("memory_search", {
            "datapack_id": await self.datapack(device), "conversation_id": await self.profile(device),
            "query": query, "limit": limit,
        })  # fmt: skip
        texts = (
            m.get("data") or m.get("memory") or m.get("text") or m.get("content") for m in _items(found, "memories", "results")
        )
        return list(dict.fromkeys(str(t) for t in texts if t))[:limit]  # the live search repeats hits

    async def log_step(self, device: str, run_id: str, goal: str, step: dict[str, Any]) -> None:
        """One Meko conversation per agent run; each step is a message with the action and the reasoning."""
        datapack = await self.datapack(device)
        meta = {"kind": "agent_run", "task": str(step.get("task", ""))}
        conv = await self._once(
            f"meko:run:{run_id}", lambda: self._new_conversation(TASKS, goal, meta, datapack)
        )
        await self._call("conversation_add_message", {
            "conversation_id": conv, "agent_id": TASKS, "datapack_id": datapack,
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
            "conversation_id": conv, "agent_id": TASKS, "include_messages": True,
            "datapack_id": await self.datapack(device),
        })  # fmt: skip
        return list(_items(got, "messages"))

    async def stored_texts(self, device: str) -> set[str]:
        """Every memory text already in the elder's datapack (any agent, any backend): seeding skips these."""
        got = await self._call("memory_get_all", {  # datapack-wide, like memory_search; a conversation is required
            "datapack_id": await self.datapack(device), "conversation_id": await self.profile(device), "page_size": 200,
        })  # fmt: skip
        texts = (
            m.get("data") or m.get("memory") or m.get("text") or m.get("content") for m in _items(got, "memories", "results")
        )
        return {str(t).strip().lower() for t in texts if t}

    async def save_state(self, device: str, name: str, state: dict[str, Any]) -> None:
        """A compiled ability's state after a run: one conversation per ability, one message per change."""
        datapack = await self.datapack(device)
        conv = await self._once(f"meko:ability:{device}:{name}", lambda: self._new_conversation(
            ABILITIES, f"ability {name}", {"kind": "ability_state", "ability": name}, datapack))  # fmt: skip
        await self._call("conversation_add_message", {
            "conversation_id": conv, "agent_id": ABILITIES, "datapack_id": datapack,
            "input": f"state of {name}", "output": json.dumps(state, ensure_ascii=False)[:8000],
            "metadata": json.dumps({"ability": name}),
        })  # fmt: skip

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
