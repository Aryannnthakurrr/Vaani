"""Path A: the dispatch request built from the note, the mock ambulance API, and the call-108 fallback."""

from __future__ import annotations

import sys
import time

from fastapi.testclient import TestClient

from app.care import dispatch, triage
from app.care.models import Location
from app.care.session import CareSession
from tests.conftest import HEADERS, REPO

sys.path.insert(0, str(REPO))  # mock_ambulance lives at the repo root, beside backend/

from mock_ambulance import polyline  # noqa: E402
from mock_ambulance.app import app as mock_app  # noqa: E402
from mock_ambulance.sim import STORE  # noqa: E402

KEY = {"X-Api-Key": "dev-key"}


def _cardiac_session() -> CareSession:
    s = CareSession(id="s_test", device="d")
    s.location = Location(lat=28.46, lng=77.03, accuracy_m=12, address="Sector 18")
    triage.record_utterance(s, "Chest pain going to the left arm, sweating a lot")
    s.note = triage.template_note(s)
    triage._finalize(s, s.note)
    return s


def test_dispatch_request_from_note() -> None:
    body = dispatch.build_request(_cardiac_session(), landmark="Opposite the temple")
    assert body["priority"] in ("P1", "P2") and body["service_level"] == "ALS"
    assert body["incident"]["category"] == "CARDIAC"
    assert body["location"]["landmark"] == "Opposite the temple"
    assert body["clinical"]["atmist"]["signs"].startswith("Chest pain with warning signs")
    assert "EMERGENCY ASSESSMENT" in body["clinical"]["triage_note_text"]
    assert [dispatch.priority(a) for a in (1, 2, 3, 4, 5)] == ["P1", "P2", "P3", "P4", "P4"]


def test_polyline_round_trip() -> None:
    pts = [(28.46, 77.03), (28.47, 77.031), (28.475, 77.04)]
    back = polyline.decode(polyline.encode(pts))
    assert all(abs(a - b) < 1e-5 for p, q in zip(pts, back, strict=True) for a, b in zip(p, q, strict=True))


def test_mock_api_errors(monkeypatch) -> None:
    body = dispatch.build_request(_cardiac_session())
    with TestClient(mock_app) as c:
        assert c.post("/v1/dispatch/requests", json=body).status_code == 401
        bad = {**body, "location": {}}
        assert c.post("/v1/dispatch/requests", json=bad, headers=KEY).status_code == 400
        no_units = c.post("/v1/dispatch/requests", json=body, headers={**KEY, "X-Debug-No-Units": "1"})
        assert no_units.status_code == 503 and no_units.json()["detail"]["code"] == "NO_UNITS_AVAILABLE"
        assert c.get("/v1/dispatch/dsp_nope", headers=KEY).status_code == 404


def test_mock_ambulance_drives_to_the_patient(monkeypatch) -> None:
    monkeypatch.setenv("SIM_SPEED_MULTIPLIER", "400")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    body = dispatch.build_request(_cardiac_session())
    with TestClient(mock_app) as c:
        created = c.post("/v1/dispatch/requests", json=body, headers=KEY)
        assert created.status_code == 201
        did = created.json()["dispatch_id"]
        seen: set[str] = set()
        etas: list[int] = []
        for _ in range(200):
            d = c.get(f"/v1/dispatch/{did}", headers=KEY).json()
            seen.add(d["status"])
            if d["eta_sec"] is not None:
                etas.append(d["eta_sec"])
            if d["status"] == "ARRIVED_AT_SCENE":
                break
            time.sleep(0.05)
        assert {"ASSIGNED", "ARRIVED_AT_SCENE"} <= seen
        assert d["vehicle"]["type"] == "ALS" and d["crew"]["driver"]["name"]
        assert etas == sorted(etas, reverse=True) and etas[-1] == 0
        assert polyline.decode(d["route_polyline"])[-1] == (round(28.46, 5), round(77.03, 5))
        page = c.get(f"/driver/{did}?key=dev-key")
        assert page.status_code == 200 and did in page.text


def test_mock_cancel_rules(monkeypatch) -> None:
    monkeypatch.setenv("SIM_SPEED_MULTIPLIER", "1")
    body = dispatch.build_request(_cardiac_session())
    with TestClient(mock_app) as c:
        did = c.post("/v1/dispatch/requests", json=body, headers=KEY).json()["dispatch_id"]
        ok = c.post(f"/v1/dispatch/{did}/cancel", json={"reason": "PATIENT_OK"}, headers=KEY)
        assert ok.json()["status"] == "CANCELLED"
        other = c.post("/v1/dispatch/requests", json=body, headers=KEY).json()["dispatch_id"]
        STORE.items[other].status = "PATIENT_ON_BOARD"
        assert (
            c.post(f"/v1/dispatch/{other}/cancel", json={"reason": "OTHER"}, headers=KEY).status_code == 409
        )


def test_unreachable_dispatch_tells_the_app_to_call_108(client, monkeypatch) -> None:
    monkeypatch.setenv("MOCK_AMBULANCE_BASE_URL", "http://127.0.0.1:9")  # nothing listens here
    sid = client.post(
        "/care/session", json={"location": {"lat": 28.46, "lng": 77.03}}, headers=HEADERS
    ).json()["session_id"]
    note = client.post(
        f"/care/{sid}/intake", json={"text": "He passed out and is not responding"}, headers=HEADERS
    )
    assert note.json()["route"] == "A"
    assert client.post(f"/care/{sid}/emergency/alert", headers=HEADERS).json()["countdown_sec"] >= 5
    assert client.post(f"/care/{sid}/emergency/dispatch", json={}, headers=HEADERS).json() == {
        "started": True
    }
    for _ in range(100):
        events = client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]
        failed = [e for e in events if e["type"] == "dispatch_failed"]
        if failed:
            break
        time.sleep(0.05)
    assert failed and failed[0]["data"]["reason"] == "UNREACHABLE"
    assert client.post(f"/care/{sid}/emergency/cancel", headers=HEADERS).json() == {"cancelled": True}


def test_mock_ambulance_takes_the_patient_to_hospital(monkeypatch) -> None:
    monkeypatch.setenv("SIM_SPEED_MULTIPLIER", "1000")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    body = dispatch.build_request(_cardiac_session())
    with TestClient(mock_app) as c:
        did = c.post("/v1/dispatch/requests", json=body, headers=KEY).json()["dispatch_id"]
        for _ in range(300):
            d = c.get(f"/v1/dispatch/{did}", headers=KEY).json()
            if d["status"] == "COMPLETED":
                break
            time.sleep(0.05)
        steps = [t["status"] for t in d["timeline"]]
        assert steps == [
            "RECEIVED", "ASSIGNED", "EN_ROUTE", "ARRIVED_AT_SCENE", "PATIENT_ON_BOARD",
            "EN_ROUTE_TO_HOSPITAL", "ARRIVED_AT_HOSPITAL", "COMPLETED",
        ]  # fmt: skip
        assert d["destination_hospital"]["name"]
        assert c.post(f"/v1/dispatch/{did}/cancel", json={"reason": "OTHER"}, headers=KEY).status_code == 409


def test_no_location_fails_honestly(client) -> None:  # noqa: ANN001
    sid = client.post("/care/session", json={}, headers=HEADERS).json()["session_id"]
    client.post(f"/care/{sid}/intake", json={"text": "He passed out and is not responding"}, headers=HEADERS)
    out = client.post(f"/care/{sid}/emergency/dispatch", json={}, headers=HEADERS).json()
    assert out == {"started": False, "reason": "NO_LOCATION"}
    events = client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]
    (failed,) = [e for e in events if e["type"] == "dispatch_failed"]
    assert (
        failed["data"]["family_alerted"] is False
    )  # no family contacts: the app must not claim it told them
