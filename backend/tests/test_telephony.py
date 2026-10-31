"""Real clinic calls over Twilio: signing, TwiML, the allow-list and a full scripted conversation via webhooks."""

from __future__ import annotations

import pytest

from app.telephony import twiml
from app.telephony.calls import normalize_number

TOKEN = "test-auth-token"
BASE = "https://saathi.example.test"
H = {"X-Saathi-Device": "test-device-123"}
APPT = {"doctor": "Dr. Anil Mehta", "clinic": "Mehta Heart Clinic", "start_iso": "2026-11-01T16:00:00"}


def test_signature_roundtrip_and_tamper() -> None:
    params = {"CallSid": "CA123", "SpeechResult": "Yes, confirmed"}
    sig = twiml.signature(TOKEN, BASE + "/calls/twilio/gather?s=abc", params)
    assert twiml.signature_valid(TOKEN, BASE + "/calls/twilio/gather?s=abc", params, sig)
    assert not twiml.signature_valid(TOKEN, BASE + "/calls/twilio/gather?s=abd", params, sig)
    assert not twiml.signature_valid(
        TOKEN, BASE + "/calls/twilio/gather?s=abc", {**params, "SpeechResult": "No"}, sig
    )
    assert not twiml.signature_valid("", BASE, params, sig)


def test_twiml_escapes_and_listens() -> None:
    xml = twiml.say_and_listen('Dr. Mehta & "Sons" <clinic>', BASE + "/g?s=1&x=2", hints="Mehta, haan")
    assert 'Dr. Mehta &amp; "Sons" &lt;clinic&gt;' in xml
    assert 'action="https://saathi.example.test/g?s=1&amp;x=2"' in xml
    assert 'input="speech"' in xml and 'language="en-IN"' in xml
    assert twiml.say_and_hang_up("Bye").endswith("<Hangup/></Response>")


def test_normalize_indian_numbers() -> None:
    for raw in ("7065220661", "07065220661", "+91 70652 20661", "+917065220661"):
        assert normalize_number(raw) == "+917065220661"
    assert normalize_number("") == ""


@pytest.fixture()
def tel(client, monkeypatch: pytest.MonkeyPatch):  # noqa: ANN001, ANN201
    monkeypatch.setenv("TWILIO_ACCOUNT_SID", "ACtest")
    monkeypatch.setenv("TWILIO_AUTH_TOKEN", TOKEN)
    monkeypatch.setenv("TWILIO_FROM_NUMBER", "+15005550006")
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "7065220661")
    monkeypatch.setenv("PUBLIC_BASE_URL", BASE)
    from app.config import get_settings

    get_settings.cache_clear()
    placed: list[dict[str, str]] = []

    def fake_place(cfg, to, voice_url, status_url):  # noqa: ANN001, ANN202
        placed.append({"to": to, "voice": voice_url, "status": status_url})
        return "CAfake"

    monkeypatch.setattr("app.routers.telephony.place_call", fake_place)
    yield client, placed
    get_settings.cache_clear()


def _twilio_post(client, url: str, params: dict[str, str]):  # noqa: ANN001, ANN202
    path = url.removeprefix(BASE)
    sig = twiml.signature(TOKEN, url, params)
    return client.post(path, data=params, headers={"X-Twilio-Signature": sig})


def test_full_call_over_webhooks(tel) -> None:  # noqa: ANN001
    client, placed = tel
    r = client.post(
        "/calls/clinic/real/start", json={"patient_name": "Kamla", "appointment": APPT}, headers=H
    )
    assert r.status_code == 200, r.text
    session = r.json()
    assert session["status"] == "dialing" and placed[0]["to"] == "+917065220661"
    voice_url = placed[0]["voice"]

    first = _twilio_post(client, voice_url, {"CallSid": "CAfake"})
    assert first.status_code == 200 and "<Gather" in first.text and "Kamla" in first.text
    gather_url = BASE + "/calls/twilio/gather?s=" + session["id"]

    second = _twilio_post(
        client, gather_url, {"CallSid": "CAfake", "SpeechResult": "Yes, it is confirmed for 4 pm"}
    )
    assert "waiting time" in second.text and "<Gather" in second.text
    third = _twilio_post(client, gather_url, {"CallSid": "CAfake", "SpeechResult": "About 25 minutes"})
    assert "<Hangup/>" in third.text

    state = client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()
    assert state["done"] and state["confirmed"] is True and state["waiting_minutes"] == 25
    assert [line["role"] for line in state["transcript"]] == ["agent", "clinic", "agent", "clinic", "agent"]
    assert "25 minutes" in state["summary"]

    assert _twilio_post(client, placed[0]["status"], {"CallStatus": "completed"}).status_code == 204
    assert client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()["status"] == "completed"


def test_silence_reprompts_then_hangs_up(tel) -> None:  # noqa: ANN001
    client, placed = tel
    session = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H).json()
    _twilio_post(client, placed[0]["voice"], {"CallSid": "CAfake"})
    url = BASE + "/calls/twilio/gather?s=" + session["id"]
    assert "could not hear you" in _twilio_post(client, url, {"SpeechResult": ""}).text
    assert "<Hangup/>" in _twilio_post(client, url, {"SpeechResult": ""}).text


def test_rejects_forged_webhooks_and_unlisted_numbers(tel) -> None:  # noqa: ANN001
    client, placed = tel
    session = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H).json()
    forged = client.post(
        f"/calls/twilio/voice?s={session['id']}",
        data={"CallSid": "x"},
        headers={"X-Twilio-Signature": "bogus"},
    )
    assert forged.status_code == 403
    other = client.post("/calls/clinic/real/start", json={"appointment": APPT, "to": "9999999999"}, headers=H)
    assert other.status_code == 403
    assert client.post("/calls/clinic/real/start", json={"appointment": APPT}).status_code == 401


def test_start_needs_twilio_config(client, monkeypatch: pytest.MonkeyPatch) -> None:  # noqa: ANN001
    monkeypatch.setenv("TWILIO_FROM_NUMBER", "")
    r = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H)
    assert r.status_code == 503


def test_hangup_ends_session(tel, monkeypatch: pytest.MonkeyPatch) -> None:  # noqa: ANN001
    client, _ = tel
    ended: list[str] = []
    monkeypatch.setattr("app.routers.telephony.hang_up", lambda cfg, sid: ended.append(sid))
    session = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H).json()
    state = client.post(f"/calls/clinic/real/{session['id']}/hangup", headers=H).json()
    assert ended == ["CAfake"] and state["done"] and state["status"] == "completed"
