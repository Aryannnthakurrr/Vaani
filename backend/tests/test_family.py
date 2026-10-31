from __future__ import annotations

import time
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.db import Device
from app.family.page import humanize_age
from tests.conftest import DEVICE, HEADERS

CHAT_ID = "987654321"


def _configure(client, user_name: str = "Kamla") -> str:
    resp = client.post(
        "/welfare/config",
        headers=HEADERS,
        json={
            "device_id": DEVICE,
            "user_name": user_name,
            "contacts": [{"name": "Rohit", "telegram_chat_id": CHAT_ID}],
            "check_time": "12:00",
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    return body["dashboard_path"]


def test_config_returns_dashboard_path(client) -> None:
    path = _configure(client)
    assert path.startswith(f"/family/{DEVICE}?key=")
    assert len(path.split("key=")[1]) == 16


def test_dashboard_renders_status_contacts_and_alerts(client) -> None:
    path = _configure(client)
    client.post(
        "/welfare/heartbeat", headers=HEADERS, json={"device_id": DEVICE, "ts": int(time.time() * 1000)}
    )
    client.post(
        "/scam/notify_family", headers=HEADERS, json={"device_id": DEVICE, "summary": "OTP during a call."}
    )
    client.post("/welfare/test", headers=HEADERS, json={"device_id": DEVICE})

    resp = client.get(path)  # no device header: family open it in a browser
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/html")
    html = resp.text
    assert "Kamla ji" in html
    assert "Rohit" in html
    assert "Scam Shield" in html and "OTP during a call." in html
    assert "Welfare check (test)" in html
    assert 'class="card status ok"' in html
    assert 'http-equiv="refresh" content="60"' in html
    assert CHAT_ID not in html
    assert "<script" not in html


@pytest.mark.parametrize("key", ["", "0000000000000000", "wrong"])
def test_bad_key_is_404(client, key: str) -> None:
    _configure(client)
    assert client.get(f"/family/{DEVICE}?key={key}").status_code == 404
    assert client.get(f"/family/{DEVICE}").status_code == 404


def test_unknown_device_is_404_even_with_its_valid_key(client) -> None:
    from app.config import get_settings
    from app.family.links import dashboard_path

    path = dashboard_path(get_settings(), "never-configured-device")
    assert client.get(path).status_code == 404


def test_user_name_is_escaped(client) -> None:
    path = _configure(client, user_name="<script>alert(1)</script>")
    html = client.get(path).text
    assert "<script>alert(1)</script>" not in html
    assert "&lt;script&gt;" in html


def test_status_bands() -> None:
    now = datetime(2026, 10, 31, 12, 0, tzinfo=ZoneInfo("Asia/Kolkata"))
    ms = int(now.timestamp() * 1000)
    hour = 3_600_000
    assert humanize_age(ms - 2 * hour, now)[0] == "ok"
    assert humanize_age(ms - 15 * hour, now)[0] == "amber"
    assert humanize_age(ms - 30 * hour, now)[0] == "red"
    assert humanize_age(None, now)[0] == "red"
    assert humanize_age(ms - 60_000, now)[1] == "Just now"


def test_alert_links_only_when_public_url_set(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.config import Settings
    from app.family.links import family_key, key_matches, with_dashboard_link
    from app.welfare.telegram import start_reply

    base = dict(
        gemini_api_key="",
        sarvam_api_key="",
        safe_browsing_api_key="",
        telegram_bot_token="",
        mock=True,
        sandbox_enabled=False,
        welfare_check_time="12:00",
        welfare_prompt_time="11:30",
        timezone="Asia/Kolkata",
        database_path=None,
        fixtures_dir=None,
        dashboard_secret="s3cret",
    )
    plain = Settings(**base)
    public = Settings(**{**base, "public_base_url": "https://saathi.example"})
    assert with_dashboard_link(plain, "dev-1", "hello") == "hello"
    linked = with_dashboard_link(public, "dev-1", "hello")
    assert (
        linked.startswith("hello\n")
        and f"https://saathi.example/family/dev-1?key={family_key(public, 'dev-1')}" in linked
    )
    assert key_matches(public, "dev-1", family_key(public, "dev-1"))
    assert not key_matches(public, "dev-2", family_key(public, "dev-1"))
    assert family_key(plain, "dev-1") != family_key(
        Settings(**{**base, "dashboard_secret": "other"}), "dev-1"
    )
    reply = start_reply(42, "Rohit", [("Kamla", "https://saathi.example/family/x?key=y")])
    assert "chat ID is 42" in reply and "Family page for Kamla" in reply
    assert Device("d", "n", [], None, None, None).contacts == []
