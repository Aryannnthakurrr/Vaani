"""Saathi backend entry point: `uvicorn app.main:app --port 8000`."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable

from fastapi import FastAPI, Request, Response

from app import usage
from app.config import get_settings
from app.deps import get_db
from app.llm.base import get_llm
from app.routers import (
    abilities,
    agent,
    calls,
    care,
    family,
    health,
    links,
    live,
    skills,
    telephony,
    voice,
    voice_live,
    welfare,
)
from app.routers import (
    usage as usage_router,
)
from app.skills import get_code_skills, get_engine
from app.speech import sarvam_tts
from app.welfare import telegram
from app.welfare.scheduler import start_scheduler

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("saathi")
# httpx logs full request URLs at INFO, and Telegram puts the bot token in the URL path. Keep secrets out of logs.
for _noisy in ("httpx", "httpx2", "httpcore", "mcp", "google_genai"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)


async def _load_personas() -> None:
    from app.abilities import get_abilities

    try:
        await get_abilities().seed()
        await get_abilities().verify()
    except Exception as exc:  # noqa: BLE001 - a broken fixture never stops the backend
        log.warning("personas not loaded: %s", type(exc).__name__)


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    log.info(
        "starting mock=%s sarvam=%s telegram=%s sandbox=%s",
        settings.mock,
        settings.sarvam_enabled,
        settings.telegram_enabled,
        settings.sandbox_enabled,
    )
    # Import the Sarvam SDK and open its client now, not inside the first request's deadline.
    await asyncio.to_thread(sarvam_tts.warm_up, settings)
    scheduler = start_scheduler(settings, get_db())
    # Learning loop: compile routines from successful runs in the background (docs/LEARNING.md).
    scheduler.add_job(get_engine().compile_pending, "interval", minutes=10, args=[get_llm(settings)],
                      id="skill_compile", max_instances=1, coalesce=True)  # fmt: skip
    scheduler.add_job(get_code_skills().compile, "interval", minutes=30, args=[get_llm(settings)],
                      id="code_skill_compile", max_instances=1, coalesce=True)  # fmt: skip
    scheduler.add_job(get_engine().store.purge_old_runs, "interval", hours=24, id="skill_purge")
    # The demo users and their compiled abilities: seeded on every backend, then tested in the sandbox.
    personas = asyncio.create_task(_load_personas())
    poller = (
        asyncio.create_task(telegram.poll_start_commands(settings, get_db()))
        if settings.telegram_enabled
        else None
    )
    try:
        yield
    finally:
        personas.cancel()
        scheduler.shutdown(wait=False)
        if poller:
            poller.cancel()


app = FastAPI(title="Saathi backend", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def access_log(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
    """Logs route, status, size and latency only — never request bodies. Also meters usage per device."""
    started = time.perf_counter()
    device = request.headers.get("X-Saathi-Device", "")
    token = usage.current_device.set(device)
    try:
        response = await call_next(request)
    finally:
        usage.current_device.reset(token)
    route = getattr(
        request.scope.get("route"), "path", request.url.path
    )  # template, e.g. /care/{session_id}/events
    usage.METER.request(
        device,
        f"{request.method} {route}",
        int(request.headers.get("content-length", "0") or 0),
        int(response.headers.get("content-length", "0") or 0),
        (time.perf_counter() - started) * 1000,
    )
    log.info(
        "%s %s status=%d req_bytes=%s latency_ms=%d",
        request.method,
        request.url.path,
        response.status_code,
        request.headers.get("content-length", "0"),
        (time.perf_counter() - started) * 1000,
    )
    return response


for module in (
    health,
    agent,
    links,
    welfare,
    voice,
    live,
    voice_live,
    family,
    calls,
    telephony,
    skills,
    abilities,
    care,
    usage_router,
):
    app.include_router(module.router)
