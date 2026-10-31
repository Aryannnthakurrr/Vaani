from __future__ import annotations

import json

import pytest

from tests.conftest import DEVICE, FIXTURES, HEADERS, load_screens, serialize_screen


def test_health_is_open_and_reports_mock(client) -> None:
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["mock"] is True
    assert body["models"]["agent_step"]


@pytest.mark.parametrize(
    "path", ["/intent", "/agent/step", "/classify/messages", "/links/check", "/welfare/heartbeat"]
)
def test_routes_require_device_header(client, path: str) -> None:
    assert client.post(path, json={}).status_code == 401


@pytest.mark.parametrize(
    ("utterance", "style"),
    [("book a cab", "en"), ("कैब बुक करो", "hi"), ("Apollo jaane ke liye cab book kar do", "hinglish")],
)
def test_intent_routes_cab_in_users_style(client, utterance: str, style: str) -> None:
    body = client.post("/intent", json={"utterance": utterance}, headers=HEADERS).json()
    assert body["intent"] == "book_cab"
    assert body["reply_style"] == style
    if style == "hinglish":
        assert body["slots"]["destination"] == "Apollo"
        assert "Apollo ke liye" in body["reply_text"]
    if style == "hi":
        assert "कैब" in body["reply_text"]


@pytest.mark.parametrize(
    ("utterance", "intent"),
    [
        ("book a cab to Apollo Hospital", "book_cab"),
        ("mujhe dil ke doctor ko dikhana hai", "book_doctor"),
        ("how do I send this photo to my grandson", "guide"),
        ("Parle-G biscuits order kar do", "shop_online"),
        ("kal subah safai wali bulao", "book_house_help"),
        ("Pronto se bartan wali bula do", "book_house_help"),
        ("I need someone to do the laundry", "book_house_help"),
        ("Papa ki tabiyat theek nahi, seene mein dard hai", "care"),
        ("call an ambulance", "care"),
        ("what are my updates today", "digest"),
        ("namaste", "chitchat"),
        ("check the expiry on this packet", "camera"),
    ],
)
def test_intent_variety(client, utterance: str, intent: str) -> None:
    assert client.post("/intent", json={"utterance": utterance}, headers=HEADERS).json()["intent"] == intent


@pytest.mark.parametrize(
    ("utterance", "service"),
    [
        ("Pronto se bartan wali bula do", "dishwashing"),
        ("kitchen ki safai karwani hai", "kitchen cleaning"),
        ("ghar ki safai wali bulao", "home cleaning"),
    ],
)
def test_house_help_service(client, utterance: str, service: str) -> None:
    body = client.post("/intent", json={"utterance": utterance}, headers=HEADERS).json()
    assert body["slots"]["service"] == service


def _walk(client, scenario: str) -> list[dict]:
    script = json.loads((FIXTURES / "agent_scripts" / f"{scenario}.json").read_text("utf-8"))
    screens = load_screens(scenario)
    history: list[dict] = []
    actions: list[dict] = []
    idx = 0
    for _ in range(len(script["steps"]) + 2):
        req = {
            "goal": " ".join(script["goal_keywords"][:1]),
            "mode": script["mode"],
            "language": "en",
            "screen_text": serialize_screen(screens[idx]),
            "package": screens[idx]["package"],
            "history": history,
            "task_hint": scenario,
        }
        action = client.post("/agent/step", json=req, headers=HEADERS).json()
        actions.append(action)
        if action["action"] in {"done", "fail"}:
            break
        history.append({"action": action, "reason": action.get("reason", "")})
        if action["action"] in {
            "tap",
            "tap_xy",
            "type",
            "scroll",
            "back",
            "open_app",
            "open_deeplink",
            "confirm",
            "highlight",
        }:
            idx = min(idx + 1, len(screens) - 1)
    return actions


@pytest.mark.parametrize("scenario", sorted(p.stem for p in (FIXTURES / "agent_scripts").glob("*.json")))
def test_mock_agent_replays_every_scenario(client, scenario: str) -> None:
    actions = _walk(client, scenario)
    assert actions[-1]["action"] == "done", actions
    script = json.loads((FIXTURES / "agent_scripts" / f"{scenario}.json").read_text("utf-8"))
    if script["mode"] == "GUIDE":
        assert {a["action"] for a in actions} <= {"highlight", "ask_user", "done", "fail"}
    else:
        assert any(a["action"] == "confirm" for a in actions) or scenario.startswith("settings")


def test_mock_agent_picks_script_from_goal(client) -> None:
    screen = serialize_screen(load_screens("uber_book")[0])
    body = client.post(
        "/agent/step",
        json={"goal": "book a cab to Apollo", "mode": "ACT", "screen_text": screen},
        headers=HEADERS,
    ).json()
    assert body == {"action": "tap", "id": 1, "reason": "Open destination search"}


def test_unknown_goal_fails_kindly(client) -> None:
    body = client.post(
        "/agent/step", json={"goal": "launch the rocket", "screen_text": "x"}, headers=HEADERS
    ).json()
    assert body["action"] == "fail"
    assert "show you" in body["message"]


def test_classify_matches_demo_expectations(client) -> None:
    demo = json.loads((FIXTURES / "messages" / "demo_sms.json").read_text("utf-8"))
    cloud = [m for m in demo if not m.get("from_contact")]
    msgs = [{"id": str(i), "sender": m["sender"], "text": m["text"]} for i, m in enumerate(cloud)]
    results = client.post("/classify/messages", json={"messages": msgs}, headers=HEADERS).json()["results"]
    for m, r in zip(cloud, results, strict=True):
        assert r["category"] == m["expected_category"], (m["text"], r)
    bill = results[0]
    assert bill["amount"] == 1240.0 and bill["due_date"] == "2026-11-08"


def test_digest_skips_promos_and_warns_on_scams(client) -> None:
    items = [
        {"category": "bill_due", "merchant": "BSES", "amount": 1240, "due_date": "2026-11-06"},
        {"category": "subscription_renewal", "merchant": "Netflix", "amount": 199, "due_date": "2026-11-01"},
        {"category": "promo", "summary": "50% off"},
        {"category": "scam_suspected", "summary": "KYC"},
    ]
    text = client.post(
        "/digest",
        json={"items": items, "reply_style": "en", "user_name": "Kamla", "today": "2026-10-31"},
        headers=HEADERS,
    ).json()["text"]
    assert text.startswith("Good morning Kamla ji.")
    assert "₹1,240" in text and "Friday" in text and "tomorrow" in text
    assert "50%" not in text and "scam" in text


def test_extract_appointment_from_practo_screen(client) -> None:
    screen = serialize_screen(load_screens("practo_book")[-1])
    body = client.post("/extract/appointment", json={"screen_text": screen}, headers=HEADERS).json()
    assert body["doctor"] == "Dr. Anil Mehta"
    assert body["clinic"] == "Mehta Heart Clinic"
    assert body["phone"] == "+919810012345"
    assert body["fee"] == 800
    assert body["start_iso"].endswith("T16:00:00")


def test_voice_returns_503_without_sarvam(client) -> None:
    files = {"file": ("a.wav", b"RIFF0000WAVE", "audio/wav")}
    assert client.post("/voice/stt", files=files, headers=HEADERS).status_code == 503
    assert client.post("/voice/tts", json={"text": "hello"}, headers=HEADERS).status_code == 503


def test_links_check_flags_apk_and_caches(client) -> None:
    body = client.post(
        "/links/check", json={"url": "http://example.xyz/files/update.apk"}, headers=HEADERS
    ).json()
    assert body["verdict"] == "dangerous" and not body["cached"]
    again = client.post(
        "/links/check", json={"url": "http://example.xyz/files/update.apk"}, headers=HEADERS
    ).json()
    assert again["cached"] is True


def test_links_check_official_domain_is_safe(client, monkeypatch) -> None:
    body = client.post("/links/check", json={"url": "https://www.onlinesbi.sbi/"}, headers=HEADERS).json()
    assert body["verdict"] == "safe"


def test_welfare_flow_logs_alert_without_telegram(client) -> None:
    cfg = {
        "device_id": DEVICE,
        "user_name": "Kamla",
        "contacts": [{"name": "Rohit", "telegram_chat_id": "12345"}],
    }
    assert client.post("/welfare/config", json=cfg, headers=HEADERS).json()["ok"] is True
    assert client.post(
        "/welfare/heartbeat", json={"device_id": DEVICE, "ts": 1_761_800_000_000}, headers=HEADERS
    ).json() == {"ok": True}
    body = client.post("/welfare/test", json={"device_id": DEVICE}, headers=HEADERS).json()
    assert body["logged"] is True and body["sent"] == []
    assert "Kamla ji" in body["text"]
    scam = client.post(
        "/scam/notify_family",
        json={"device_id": DEVICE, "summary": "OTP arrived during a call."},
        headers=HEADERS,
    ).json()
    assert scam["logged"] is True

    from app.deps import get_db

    kinds = [a["kind"] for a in get_db().alerts(DEVICE)]
    assert kinds == ["welfare_test", "scam"]


def test_welfare_test_unknown_device_404(client) -> None:
    assert client.post("/welfare/test", json={"device_id": "nobody-123"}, headers=HEADERS).status_code == 404


def test_live_camera_mock_sends_card_after_two_frames(client) -> None:
    with client.websocket_connect(f"/live/camera?device={DEVICE}") as ws:
        ws.send_json({"type": "config", "reply_style": "hinglish"})
        ws.send_json({"type": "frame", "jpeg_b64": "AAAA"})
        first = ws.receive_json()
        assert first["type"] == "text"
        assert ws.receive_json()["type"] == "turn_complete"  # each reply ends with one, so the phone can speak it
        ws.send_json({"type": "frame", "jpeg_b64": "AAAA"})
        card = ws.receive_json()
        assert card["type"] == "product_card"
        assert card["card"]["mrp"] == 140 and card["card"]["expiry_status"] == "soon"
        assert "MRP" in ws.receive_json()["text"]
        assert ws.receive_json()["type"] == "turn_complete"
        ws.send_json({"type": "end"})


def test_live_camera_rejects_missing_device(client) -> None:
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect), client.websocket_connect("/live/camera") as ws:
        ws.receive_json()
