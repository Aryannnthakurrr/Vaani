"""Saathi backend entry point: `uvicorn app.main:app --port 8000`."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable

from fastapi import FastAPI, Request, Response

from app.config import get_settings
from app.deps import get_db
from app.routers import agent, calls, family, health, links, live, telephony, voice, welfare
from app.speech import sarvam_tts
from app.welfare import telegram
from app.welfare.scheduler import start_scheduler

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("saathi")


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
    poller = (
        asyncio.create_task(telegram.poll_start_commands(settings, get_db()))
        if settings.telegram_enabled
        else None
    )
    try:
        yield
    finally:
        scheduler.shutdown(wait=False)
        if poller:
            poller.cancel()


app = FastAPI(title="Saathi backend", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def access_log(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
    """Logs route, status, size and latency only — never request bodies."""
    started = time.perf_counter()
    response = await call_next(request)
    log.info(
        "%s %s status=%d req_bytes=%s latency_ms=%d",
        request.method,
        request.url.path,
        response.status_code,
        request.headers.get("content-length", "0"),
        (time.perf_counter() - started) * 1000,
    )
    return response


for module in (health, agent, links, welfare, voice, live, family, calls, telephony):
    app.include_router(module.router)
