"""Welfare check: alert family when the phone has not been unlocked since 20:00 the previous day.

`devices_needing_alert` is pure so it can be tested with fixed datetimes. The APScheduler job runs every
5 minutes and alerts each device once per day after that device's check time.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.config import Settings
from app.db import Database, Device
from app.family.links import with_dashboard_link
from app.welfare import telegram

log = logging.getLogger("saathi.welfare")
CUTOFF = time(20, 0)  # "since 8 pm the previous day"


@dataclass
class DueAlert:
    device: Device
    text: str
    day: date


def _parse_hhmm(value: str | None, default: str) -> time:
    try:
        hh, mm = (value or default).split(":")
        return time(int(hh), int(mm))
    except ValueError:
        hh, mm = default.split(":")
        return time(int(hh), int(mm))


def last_seen_phrase(last_ms: int | None, now: datetime) -> str:
    if not last_ms:
        return "a long time"
    seen = datetime.fromtimestamp(last_ms / 1000, tz=now.tzinfo)
    clock = seen.strftime("%I:%M %p").lstrip("0").lower()
    days = (now.date() - seen.date()).days
    if days == 0:
        return f"today {clock}"
    if days == 1:
        return f"yesterday {clock}"
    return f"{seen.strftime('%d %b')} {clock}"


def alert_text(device: Device, now: datetime) -> str:
    if not device.last_heartbeat_ms:
        return (
            f"Saathi welfare check: {device.user_name} ji hasn't used their phone for a long time. "
            "Please check on them."
        )
    return (
        f"Saathi welfare check: {device.user_name} ji hasn't used their phone since "
        f"{last_seen_phrase(device.last_heartbeat_ms, now)}. Please check on them."
    )


def devices_needing_alert(
    now: datetime, devices: list[Device], default_check: str = "12:00"
) -> list[DueAlert]:
    """`now` must be timezone-aware in the welfare timezone."""
    due: list[DueAlert] = []
    today = now.date()
    cutoff = datetime.combine(today - timedelta(days=1), CUTOFF, tzinfo=now.tzinfo)
    for device in devices:
        if not device.contacts or device.last_alert_date == today.isoformat():
            continue
        check_at = datetime.combine(today, _parse_hhmm(device.check_time, default_check), tzinfo=now.tzinfo)
        if now < check_at:
            continue
        last = device.last_heartbeat_ms
        if last is not None and datetime.fromtimestamp(last / 1000, tz=now.tzinfo) >= cutoff:
            continue
        due.append(DueAlert(device=device, text=alert_text(device, now), day=today))
    return due


async def send_to_family(settings: Settings, db: Database, device: Device, kind: str, text: str) -> list[str]:
    sent: list[str] = []
    # Telegram gets the family-page link; the alert log (shown on that page) keeps the plain text.
    message = with_dashboard_link(settings, device.device_id, text)
    for contact in device.contacts:
        chat_id = str(contact.get("telegram_chat_id", "")).strip()
        if chat_id and await telegram.send_message(settings, chat_id, message):
            sent.append(chat_id)
    db.log_alert(device.device_id, kind, text, sent)
    log.info("family alert kind=%s device=%s sent=%d logged=1", kind, device.device_id[:8], len(sent))
    return sent


async def run_check(settings: Settings, db: Database, now: datetime | None = None) -> list[DueAlert]:
    tz = ZoneInfo(settings.timezone)
    now = now or datetime.now(tz)
    due = devices_needing_alert(now, db.all_devices(), settings.welfare_check_time)
    for alert in due:
        await send_to_family(settings, db, alert.device, "welfare", alert.text)
        db.mark_alerted(alert.device.device_id, alert.day.isoformat())
    return due


def start_scheduler(settings: Settings, db: Database) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler(timezone=ZoneInfo(settings.timezone))
    scheduler.add_job(
        run_check,
        "interval",
        minutes=5,
        args=[settings, db],
        id="welfare_check",
        next_run_time=datetime.now(ZoneInfo(settings.timezone)) + timedelta(seconds=30),
    )
    scheduler.start()
    return scheduler
