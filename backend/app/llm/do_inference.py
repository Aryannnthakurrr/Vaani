"""DigitalOcean serverless inference (OpenAI-compatible) for the Gemma reasoning routes in models.yaml.

GeminiClient._generate sends Gemma and Qwen models here; the route's Gemini fallback still
applies when DigitalOcean is slow or down. Voice, speech and realtime routes never come here.
"""

from __future__ import annotations

import base64
import json
import os
import ssl
from types import SimpleNamespace
from typing import Any

import httpx
import truststore
from pydantic import BaseModel

URL = "https://inference.do-ai.run/v1/chat/completions"

# Verified against the operating system's certificate store (the office network inspects HTTPS with its own CA).
_client = httpx.AsyncClient(verify=truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT))


def is_do_model(model: str) -> bool:
    return model.lower().startswith(("gemma-", "qwen"))


class DoError(RuntimeError):
    """Raised with the HTTP status first ("503 …"), so the caller's retry check treats it like a Gemini error."""


def _part(item: Any) -> dict[str, Any]:
    """A google-genai content item (str or Part with inline image bytes) as an OpenAI message part."""
    if isinstance(item, str):
        return {"type": "text", "text": item}
    blob = getattr(item, "inline_data", None)
    if blob is not None and blob.data:
        data = base64.b64encode(blob.data).decode()
        return {"type": "image_url", "image_url": {"url": f"data:{blob.mime_type or 'image/jpeg'};base64,{data}"}}
    return {"type": "text", "text": str(getattr(item, "text", "") or item)}


async def generate(
    model: str, system: str, contents: list[Any], schema: type[BaseModel] | None, temperature: float
) -> tuple[str, Any]:
    """Returns (text, usage) where usage looks like Gemini's usage_metadata for the usage meter."""
    key = os.environ.get("DIGITALOCEAN_INFERENCE_KEY", "")
    if not key:
        raise DoError("401 DIGITALOCEAN_INFERENCE_KEY is not set")
    if schema is not None:
        system = (
            f"{system}\n\nReply with one JSON object only, matching this JSON schema:\n"
            f"{json.dumps(schema.model_json_schema(), ensure_ascii=False)}"
        )
    body: dict[str, Any] = {
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": [_part(c) for c in contents]}],
        "temperature": temperature,
        "max_tokens": 2048,
    }
    if schema is not None:
        body["response_format"] = {"type": "json_object"}
    resp = await _client.post(URL, json=body, headers={"Authorization": f"Bearer {key}"}, timeout=60)
    if resp.status_code != 200:
        raise DoError(f"{resp.status_code} {resp.text[:200]}")
    data = resp.json()
    text = (data["choices"][0]["message"].get("content") or "").strip()
    if text.startswith("```"):  # some answers wrap the JSON in a code fence despite json_object
        text = text.strip("`").removeprefix("json").strip()
    u = data.get("usage") or {}
    usage = SimpleNamespace(prompt_token_count=u.get("prompt_tokens", 0), candidates_token_count=u.get("completion_tokens", 0))
    return text, usage
