"""GET /health — unauthenticated status for scripts and the app's settings screen."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app.config import Settings
from app.deps import settings_dep

router = APIRouter()


@router.get("/health")
async def health(settings: Settings = Depends(settings_dep)) -> dict[str, Any]:
    models = {name: cfg.get("model") for name, cfg in settings.models.items() if isinstance(cfg, dict)}
    return {
        "status": "ok",
        "mock": settings.mock,
        "sarvam": settings.sarvam_enabled,
        "telegram": settings.telegram_enabled,
        "sandbox": settings.sandbox_enabled,
        "models": models,
    }
