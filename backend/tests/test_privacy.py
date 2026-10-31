"""Privacy guarantees that must not regress (docs/PRIVACY_PITCH.md §8, docs/PRIVACY_AUDIT.md)."""

from __future__ import annotations

import json

KEY = {"X-Saathi-Device": "privacy-device-01", "X-Saathi-Key": "k" * 43}
OTHER = {"X-Saathi-Device": "privacy-device-02", "X-Saathi-Key": "z" * 43}


# ------------------------------------------------------------------ 4. install secret


def test_a_device_id_alone_no_longer_unlocks_a_device(client) -> None:
    assert client.get("/usage", headers=KEY).status_code == 200  # first key is kept (trust on first use)
    no_key = {"X-Saathi-Device": KEY["X-Saathi-Device"]}
    wrong = {**KEY, "X-Saathi-Key": "guess" * 9}
    assert client.get("/usage", headers=no_key).status_code == 401
    assert client.get("/usage", headers=wrong).status_code == 401
    assert client.get("/usage", headers=KEY).status_code == 200


def test_only_a_hash_of_the_key_is_stored(client) -> None:
    from app.deps import get_db

    client.get("/usage", headers=KEY)
    stored = get_db().device_key_hash(KEY["X-Saathi-Device"])
    assert stored and KEY["X-Saathi-Key"] not in stored and len(stored) == 64


def test_live_sockets_check_the_key(client) -> None:
    import pytest
    from starlette.websockets import WebSocketDisconnect

    client.get("/usage", headers=KEY)
    with (
        pytest.raises(WebSocketDisconnect),
        client.websocket_connect(f"/live/camera?device={KEY['X-Saathi-Device']}") as ws,
    ):
        ws.receive_text()


def test_welfare_routes_act_only_on_the_callers_device(client) -> None:
    body = {"device_id": OTHER["X-Saathi-Device"], "user_name": "x", "contacts": [], "check_time": "09:00"}
    assert client.post("/welfare/config", json=body, headers=KEY).status_code == 403
    own = {**body, "device_id": KEY["X-Saathi-Device"]}
    assert client.post("/welfare/config", json=own, headers=KEY).status_code == 200
    notify = {"device_id": KEY["X-Saathi-Device"], "summary": "test"}
    assert client.post("/scam/notify_family", json=notify, headers=OTHER).status_code == 403


# ------------------------------------------------------------------ 3. links


def test_links_lose_their_personal_parts() -> None:
    from app.sandbox.url_checks import privacy_url
    from tests.conftest import FIXTURES

    cases = json.loads((FIXTURES / "link_privacy" / "cases.json").read_text("utf-8"))
    for case in cases:
        assert privacy_url(case["in"]) == case["out"], case


def test_the_server_never_checks_the_full_link(client, monkeypatch) -> None:
    from app.routers import links

    seen: list[str] = []
    real = links.analyse
    monkeypatch.setattr(links, "analyse", lambda url: seen.append(url) or real(url))
    client.post("/links/check", json={"url": "https://x.in/reset?token=abc123"}, headers=KEY)
    assert seen == ["https://x.in/reset"]


# ------------------------------------------------------------------ 10. the audit itself (docs/PRIVACY_AUDIT.md)


def _audit():  # type: ignore[no-untyped-def]
    import importlib.util

    from tests.conftest import REPO

    spec = importlib.util.spec_from_file_location("privacy_audit", REPO / "scripts" / "privacy_audit.py")
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module


def test_no_personal_value_reaches_the_logs(client) -> None:
    out = _audit().log_leaks(client)
    assert out["routes_served"] == len(out["routes"]), out[
        "routes"
    ]  # the data really went through every route
    assert out["leaked"] == []


def test_forged_and_cross_device_requests_are_blocked(client) -> None:
    out = _audit().device_auth(client)
    assert out["blocked"] == out["attempts"] and out["owner_still_works"]


def test_family_alerts_carry_no_numbers_or_symptoms() -> None:
    out = _audit().family_alerts()
    assert out["scam_summary_numbers_left"] == [] and not out["emergency_alert_has_symptoms"]


def test_inquiry_calls_hear_an_age_band() -> None:
    from app.care.orchestrator import age_band

    assert age_band("72F, knee pain since 2 days") == "70s, F, knee pain since 2 days"
    assert age_band("Papa 68 saal, ghutne mein dard") == "Papa 60s, ghutne mein dard"


def test_forget_everything(client) -> None:
    import asyncio

    from app.memory import get_memory

    asyncio.run(get_memory().remember(KEY["X-Saathi-Device"], "Daughter lives in Pune", "family", "daughter"))
    out = client.delete("/memory", headers=KEY).json()
    assert out["facts"] >= 1
    assert asyncio.run(get_memory().facts(KEY["X-Saathi-Device"])) == []
