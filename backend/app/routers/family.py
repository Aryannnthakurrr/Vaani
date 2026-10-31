"""GET /family/{device_id}?key=… — the read-only family dashboard. No device header: family open it in a browser."""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse

from app.config import Settings
from app.db import Database
from app.deps import db_dep, settings_dep
from app.family.links import key_matches
from app.family.page import render

router = APIRouter()


@router.get("/family/{device_id}", response_class=HTMLResponse)
async def family_page(
    device_id: str,
    key: str | None = None,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
) -> HTMLResponse:
    # Same 404 for a wrong key and an unknown device, so the page never confirms a device exists.
    if not key_matches(settings, device_id, key):
        raise HTTPException(status_code=404, detail="not found")
    device = db.get_device(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="not found")
    html = render(device, db.alerts(device_id), datetime.now(ZoneInfo(settings.timezone)))
    return HTMLResponse(html, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})
