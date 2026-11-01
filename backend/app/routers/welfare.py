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
# Any amount after a currency sign ("₹500", "Rs. 99"), and any other run of 3+ digits (OTPs, phone, account and card
# numbers, with or without separators). "rs" must start a word, so "hours 12" doesn't become "hou…".
_NUMBERS = re.compile(
    r"(?:₹|\b(?:rs|inr)(?![a-z])\.?)\s*\d(?:[,.]?\d)*|\d(?:[\s,.-]{0,2}\d){2,}", re.IGNORECASE
)


# Email addresses and UPI IDs (ramesh@okaxis, someone@ybl) are personal and a scammer's collection address alike.
_HANDLES = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)*", re.IGNORECASE)


def family_safe(summary: str) -> str:
    return _NUMBERS.sub("…", _HANDLES.sub("…", summary))[:300]


router = APIRouter()


@router.post("/welfare/heartbeat")
async def heartbeat(
    req: Heartbeat, db: Database = Depends(db_dep), caller: str = Depends(require_device)
) -> dict[str, bool]:
    same_device(caller, req.device_id)
    db.heartbeat(caller, req.ts)  # caller: the checked, trimmed id
    return {"ok": True}


@router.post("/welfare/config")
async def config(
    req: WelfareConfig,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    caller: str = Depends(require_device),
) -> dict[str, bool | str]:
    same_device(caller, req.device_id)
    db.configure(caller, req.user_name, [c.model_dump() for c in req.contacts], req.check_time)
    return {"ok": True, "dashboard_path": dashboard_path(settings, caller)}


@router.post("/welfare/test", response_model=SendResult)
async def welfare_test(
    req: DeviceOnly,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    caller: str = Depends(require_device),
) -> SendResult:
    same_device(caller, req.device_id)
    device = db.get_device(caller)
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
    device = db.get_device(caller)
    if device is None:
        raise HTTPException(status_code=404, detail="device not configured; POST /welfare/config first")
    text = f"Vaani Scam Shield: {device.user_name} ji may be talking to a scammer. {family_safe(req.summary)} Please call them."
    sent = await send_to_family(settings, db, device, "scam", text)
    return SendResult(text=text, sent=sent, logged=True)
