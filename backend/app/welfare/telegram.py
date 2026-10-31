"""Telegram Bot API: send family alerts and answer /start with the chat ID."""

from __future__ import annotations

import asyncio
import logging

import httpx

from app.config import Settings
from app.db import Database
from app.family.links import dashboard_url

log = logging.getLogger("saathi.telegram")
_API = "https://api.telegram.org/bot{token}/{method}"


async def send_message(
    settings: Settings, chat_id: str, text: str, client: httpx.AsyncClient | None = None
) -> bool:
    """Returns True when Telegram accepted the message. No token → logged only, returns False."""
    if not settings.telegram_enabled:
        log.info("telegram disabled; alert logged only (chat=%s)", chat_id)
        return False
    url = _API.format(token=settings.telegram_bot_token, method="sendMessage")
    own = client is None
    client = client or httpx.AsyncClient(timeout=10.0)
    try:
        resp = await client.post(url, json={"chat_id": chat_id, "text": text})
        ok = resp.status_code == 200 and resp.json().get("ok", False)
        if not ok:
            log.warning("telegram send failed status=%s", resp.status_code)
        return bool(ok)
    except httpx.HTTPError as exc:
        log.warning("telegram send error: %s", type(exc).__name__)
        return False
    finally:
        if own:
            await client.aclose()


def start_reply(
    chat_id: int | str, first_name: str | None, pages: list[tuple[str, str]] | None = None
) -> str:
    """`pages` are (user name, dashboard URL) for every device that already lists this chat as family."""
    who = f" {first_name}" if first_name else ""
    text = (
        f"Namaste{who}! Your Saathi chat ID is {chat_id}.\n"
        "Open Saathi → Settings → Family contacts, and enter this number to receive welfare and scam alerts."
    )
    for name, url in pages or []:
        text += f"\nFamily page for {name}: {url}"
    return text


def family_pages(settings: Settings, db: Database, chat_id: str) -> list[tuple[str, str]]:
    pages = []
    for device in db.all_devices():
        if any(str(c.get("telegram_chat_id", "")).strip() == chat_id for c in device.contacts):
            url = dashboard_url(settings, device.device_id)
            if url:
                pages.append((device.user_name, url))
    return pages


async def poll_start_commands(settings: Settings, db: Database) -> None:
    """Long-poll getUpdates and reply to /start with the chat ID. Runs until cancelled."""
    offset = 0
    url = _API.format(token=settings.telegram_bot_token, method="getUpdates")
    async with httpx.AsyncClient(timeout=40.0) as client:
        while True:
            try:
                resp = await client.get(url, params={"timeout": 30, "offset": offset})
                for update in resp.json().get("result", []):
                    offset = update["update_id"] + 1
                    msg = update.get("message") or {}
                    if (msg.get("text") or "").strip().startswith("/start"):
                        chat = msg["chat"]
                        pages = family_pages(settings, db, str(chat["id"]))
                        reply = start_reply(chat["id"], chat.get("first_name"), pages)
                        await send_message(settings, str(chat["id"]), reply, client)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # network blips: back off and keep polling
                log.info("telegram poll error: %s", type(exc).__name__)
                await asyncio.sleep(5)
