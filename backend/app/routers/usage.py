"""GET /usage: a device's own request, byte and model-token counts since the server started (docs/engineering/CONTRACTS.md)."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app.config import Settings
from app.deps import require_device, settings_dep
from app.usage import METER, cost

router = APIRouter()


@router.get("/usage")
def my_usage(
    device: str = Depends(require_device), settings: Settings = Depends(settings_dep)
) -> dict[str, Any]:
    snap = METER.snapshot(device)
    return {**snap, "cost": cost(snap, settings.models.get("pricing") or {})}
