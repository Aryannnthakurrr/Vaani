"""Per-device family dashboard keys and links (HMAC of the device ID, so nothing extra is stored)."""

from __future__ import annotations

import hashlib
import hmac
from urllib.parse import quote

from app.config import Settings

KEY_LENGTH = 16


def family_key(settings: Settings, device_id: str) -> str:
    digest = hmac.new(settings.dashboard_secret.encode(), device_id.encode(), hashlib.sha256).hexdigest()
    return digest[:KEY_LENGTH]


def key_matches(settings: Settings, device_id: str, key: str | None) -> bool:
    return bool(key) and hmac.compare_digest(family_key(settings, device_id), key or "")


def dashboard_path(settings: Settings, device_id: str) -> str:
    return f"/family/{quote(device_id, safe='')}?key={family_key(settings, device_id)}"


def dashboard_url(settings: Settings, device_id: str) -> str | None:
    """Absolute link for family messages, or None when PUBLIC_BASE_URL is not configured."""
    if not settings.public_base_url:
        return None
    return settings.public_base_url + dashboard_path(settings, device_id)


def with_dashboard_link(settings: Settings, device_id: str, text: str) -> str:
    url = dashboard_url(settings, device_id)
    return f"{text}\nSaathi family page: {url}" if url else text
