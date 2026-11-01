"""/welfare/* and /scam/notify_family."""

from __future__ import annotations

import re
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException

from app.config import Settings
from app.db import Database
from app.deps import db_dep, require_device, same_device, settings_dep
from app.family.links import dashboard_path
from app.schemas import DeviceOnly, FamilyNotify, Heartbeat, SendResult, WelfareConfig
from app.welfare.scheduler import alert_text, send_to_family

# Amounts, account, card and phone numbers in the phone's scam summary never reach Telegram.
_NUMBERS = re.compile(r"(₹|rs\.?|inr)?\s*\d[\d,.\s-]{2,}\d", re.IGNORECASE)


def family_safe(summary: str) -> str:
    return _NUMBERS.sub("…", summary)[:300]


router = APIRouter()


@router.post("/welfare/heartbeat")
async def heartbeat(
    req: Heartbeat, db: Database = Depends(db_dep), caller: str = Depends(require_device)
) -> dict[str, bool]:
    same_device(caller, req.device_id)
    db.heartbeat(req.device_id, req.ts)
    return {"ok": True}


@router.post("/welfare/config")
async def config(
    req: WelfareConfig,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    caller: str = Depends(require_device),
) -> dict[str, bool | str]:
    same_device(caller, req.device_id)
    db.configure(req.device_id, req.user_name, [c.model_dump() for c in req.contacts], req.check_time)
    return {"ok": True, "dashboard_path": dashboard_path(settings, req.device_id)}


@router.post("/welfare/test", response_model=SendResult)
async def welfare_test(
    req: DeviceOnly,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    caller: str = Depends(require_device),
) -> SendResult:
    same_device(caller, req.device_id)
    device = db.get_device(req.device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not configured; POST /welfare/config first")
    text = "[TEST] " + alert_text(device, datetime.now(ZoneInfo(settings.timezone)))
    sent = await send_to_family(settings, db, device, "welfare_test", text)
    return SendResult(text=text, sent=sent, logged=True)


@router.post("/scam/notify_family", response_model=SendResult)
async def notify_family(
    req: FamilyNotify,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    caller: str = Depends(require_device),
) -> SendResult:
    same_device(caller, req.device_id)
    device = db.get_device(req.device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not configured; POST /welfare/config first")
    text = f"Vaani Scam Shield: {device.user_name} ji may be talking to a scammer. {family_safe(req.summary)} Please call them."
    sent = await send_to_family(settings, db, device, "scam", text)
    return SendResult(text=text, sent=sent, logged=True)
