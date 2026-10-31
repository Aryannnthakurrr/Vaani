"""Usage metering: requests and bytes per route, and Gemini calls and tokens per model, per device (in memory).

Feeds `GET /usage` (a device's own numbers) and scripts/simulate_profiles.py (cost and bandwidth per average user).
Only counts are kept, never bodies, text or transcripts. Prices come from `models.yaml` `pricing`; with no price set,
reports show tokens and bytes without inventing a cost.
"""

from __future__ import annotations

import contextvars
import threading
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any

current_device: contextvars.ContextVar[str] = contextvars.ContextVar("current_device", default="")


@dataclass
class RouteStats:
    count: int = 0
    req_bytes: int = 0
    resp_bytes: int = 0
    total_ms: float = 0.0


@dataclass
class ModelStats:
    calls: int = 0
    prompt_tokens: int = 0
    output_tokens: int = 0


@dataclass
class DeviceUsage:
    routes: dict[str, RouteStats] = field(default_factory=lambda: defaultdict(RouteStats))
    models: dict[str, ModelStats] = field(default_factory=lambda: defaultdict(ModelStats))


class Meter:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._devices: dict[str, DeviceUsage] = defaultdict(DeviceUsage)

    def request(self, device: str, route: str, req_bytes: int, resp_bytes: int, ms: float) -> None:
        with self._lock:
            r = self._devices[device or "-"].routes[route]
            r.count += 1
            r.req_bytes += req_bytes
            r.resp_bytes += resp_bytes
            r.total_ms += ms

    def llm(self, model: str, usage: Any, device: str | None = None) -> None:
        """Records one model call; `usage` is a Gemini usage_metadata (or None)."""
        prompt = int(getattr(usage, "prompt_token_count", 0) or 0)
        output = int(getattr(usage, "candidates_token_count", 0) or 0)
        with self._lock:
            m = self._devices[(device if device is not None else current_device.get()) or "-"].models[model]
            m.calls += 1
            m.prompt_tokens += prompt
            m.output_tokens += output

    def snapshot(self, device: str | None = None) -> dict[str, Any]:
        with self._lock:
            devices = [self._devices[device]] if device is not None else list(self._devices.values())
            routes: dict[str, RouteStats] = defaultdict(RouteStats)
            models: dict[str, ModelStats] = defaultdict(ModelStats)
            for d in devices:
                for k, r in d.routes.items():
                    t = routes[k]
                    t.count, t.req_bytes, t.resp_bytes = (
                        t.count + r.count,
                        t.req_bytes + r.req_bytes,
                        t.resp_bytes + r.resp_bytes,
                    )
                    t.total_ms += r.total_ms
                for k, m in d.models.items():
                    t2 = models[k]
                    t2.calls, t2.prompt_tokens, t2.output_tokens = (
                        t2.calls + m.calls,
                        t2.prompt_tokens + m.prompt_tokens,
                        t2.output_tokens + m.output_tokens,
                    )
        return {
            "routes": {k: vars(v) for k, v in sorted(routes.items())},
            "models": {k: vars(v) for k, v in sorted(models.items())},
            "totals": {
                "requests": sum(r.count for r in routes.values()),
                "bytes_up": sum(r.req_bytes for r in routes.values()),
                "bytes_down": sum(r.resp_bytes for r in routes.values()),
                "llm_calls": sum(m.calls for m in models.values()),
                "tokens": sum(m.prompt_tokens + m.output_tokens for m in models.values()),
            },
        }

    def reset(self) -> None:
        with self._lock:
            self._devices.clear()


METER = Meter()


def cost(snapshot: dict[str, Any], pricing: dict[str, Any]) -> dict[str, Any]:
    """Cost in USD per model from `models.yaml` pricing (per million tokens). Unpriced models are listed, not guessed."""
    per_model: dict[str, float] = {}
    unpriced: list[str] = []
    for model, m in snapshot["models"].items():
        p = pricing.get(model) or {}
        if p.get("input_per_m") is None or p.get("output_per_m") is None:
            unpriced.append(model)
            continue
        per_model[model] = (
            m["prompt_tokens"] * p["input_per_m"] + m["output_tokens"] * p["output_per_m"]
        ) / 1e6
    return {"usd_by_model": per_model, "usd_total": round(sum(per_model.values()), 6), "unpriced": unpriced}
