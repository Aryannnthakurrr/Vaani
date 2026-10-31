"""Confirming an appointment while the hospital is still on the call (care/live_confirm.py)."""

from __future__ import annotations

import time

import pytest

from app.care import calls, live_confirm
from app.care.calls import CallRun
from app.care.session import CARE
from tests.conftest import HEADERS
from tests.test_care import _calm_answer, _say, _start

SECRET = "tool-secret-for-tests"


@pytest.fixture
def demo(monkeypatch):  # type: ignore[no-untyped-def]
    monkeypatch.setenv("DEMO_MODE", "1")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    monkeypatch.setenv("DEMO_PHONE_MAP", "+917000000001,+917000000002,+917000000003")
    monkeypatch.setenv("BOLNA_TOOL_SECRET", SECRET)
    monkeypatch.setattr(calls, "calls_ready", lambda: True)
    return monkeypatch


def _route_b(client) -> str:  # type: ignore[no-untyped-def]
    sid = _start(client, patient={"name": "Kamla Devi", "age": 72, "phone": "+919800000001"})
    out = _say(client, sid, "Mild chest discomfort when climbing stairs, no sweating")
    while not out["done"]:
        out = _say(client, sid, _calm_answer(out["question"]))
    assert out["route"] == "B"
    return sid


def _wait_for(client, sid: str, kind: str) -> dict:  # type: ignore[no-untyped-def]
    for _ in range(200):
        found = [
            e
            for e in client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]
            if e["type"] == kind
        ]
        if found:
            return found[-1]["data"]
        time.sleep(0.02)
    raise AssertionError(f"no {kind} event")


def test_tool_needs_the_shared_secret(client, demo) -> None:
    body = {"session_ref": "s_nope", "hospital_ref": "demo/1", "slot": "kal 11 baje"}
    assert client.post("/care/tools/check_with_patient", json=body).status_code == 401
    wrong = {"Authorization": "Bearer guess"}
    assert client.post("/care/tools/check_with_patient", json=body, headers=wrong).status_code == 401
    right = {"Authorization": f"Bearer {SECRET}"}
    out = client.post("/care/tools/check_with_patient", json=body, headers=right).json()
    assert out["decision"] == "no_answer"  # unknown session: the agent says it will call back


def test_tool_is_off_without_a_secret(client, demo) -> None:
    demo.setenv("BOLNA_TOOL_SECRET", "")
    body = {"session_ref": "s_nope", "hospital_ref": "demo/1"}
    assert (
        client.post(
            "/care/tools/check_with_patient", json=body, headers={"Authorization": "Bearer "}
        ).status_code
        == 401
    )


def test_yes_on_the_call_books_without_a_second_call(client, demo) -> None:
    modes: list[str] = []
    replies: list[dict] = []

    async def fake_run(session, hospital_id, number, mode, user_data):  # type: ignore[no-untyped-def]
        modes.append(f"{mode}:{hospital_id}")
        assert user_data["session_ref"] == session.id and user_data["hospital_ref"] == hospital_id
        if hospital_id != "demo/1":
            return CallRun(f"ex-{hospital_id}", "NO_ANSWER", [])
        replies.append(await live_confirm.ask(session, hospital_id, "Dr. Meera Iyer", "kal 11 baje", "600"))
        said = "Haan, Dr. Meera Iyer, kal 11 baje. Booked for Kamla Devi, confirmed, token number is CC-2291"
        return CallRun("ex-1", "COMPLETED", [{"role": "clinic", "text": said}])

    demo.setattr(calls, "run_call", fake_run)
    sid = _route_b(client)
    client.post(f"/care/{sid}/search", headers=HEADERS)
    ask = _wait_for(client, sid, "live_confirm")
    assert ask["hospital_id"] == "demo/1" and ask["slot"] == "kal 11 baje"
    assert client.post(
        f"/care/{sid}/live_confirm", json={"ask_id": ask["ask_id"], "yes": True}, headers=HEADERS
    ).json() == {"accepted": True}
    booked = _wait_for(client, sid, "booking_status")
    assert booked["status"] == "confirmed" and booked["during_inquiry"] is True
    assert booked["booking_ref"] == "CC-2291"
    assert replies[0]["decision"] == "yes" and replies[0]["patient_name"] == "Kamla Devi"
    assert not any(m.startswith("booking") for m in modes)
    types = [e["type"] for e in client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]]
    assert "recommendation" not in types
    # A second answer to the same question is ignored.
    again = client.post(
        f"/care/{sid}/live_confirm", json={"ask_id": ask["ask_id"], "yes": False}, headers=HEADERS
    )
    assert again.json() == {"accepted": False}


def test_no_answer_in_time_falls_back_to_the_recommendation(client, demo) -> None:
    demo.setattr(live_confirm, "WAIT_S", 0.05)
    replies: list[dict] = []

    async def fake_run(session, hospital_id, number, mode, user_data):  # type: ignore[no-untyped-def]
        if hospital_id != "demo/1":
            return CallRun(f"ex-{hospital_id}", "NO_ANSWER", [])
        replies.append(await live_confirm.ask(session, hospital_id, "Dr. Meera Iyer", "kal 11 baje", "600"))
        return CallRun(
            "ex-1", "COMPLETED", [{"role": "clinic", "text": "Dr. Meera Iyer hai, kal 11 baje slot hai"}]
        )

    demo.setattr(calls, "run_call", fake_run)
    sid = _route_b(client)
    client.post(f"/care/{sid}/search", headers=HEADERS)
    rec = _wait_for(client, sid, "recommendation")
    assert rec["primary"]["hospital_id"] == "demo/1"
    assert replies[0]["decision"] == "no_answer"
    assert not CARE.get_any(sid).live_booked  # type: ignore[union-attr]


def test_a_yes_the_transcript_does_not_confirm_is_not_called_booked(client, demo) -> None:
    async def fake_run(session, hospital_id, number, mode, user_data):  # type: ignore[no-untyped-def]
        if mode == "booking" or hospital_id != "demo/1":
            return CallRun(f"ex-{hospital_id}", "NO_ANSWER", [])
        await live_confirm.ask(session, hospital_id, "Dr. Meera Iyer", "kal 11 baje", "600")
        return CallRun(
            "ex-1",
            "COMPLETED",
            [{"role": "clinic", "text": "Dr. Meera Iyer, kal 11 baje. Sorry, the line dropped"}],
        )

    demo.setattr(calls, "run_call", fake_run)
    sid = _route_b(client)
    client.post(f"/care/{sid}/search", headers=HEADERS)
    ask = _wait_for(client, sid, "live_confirm")
    client.post(f"/care/{sid}/live_confirm", json={"ask_id": ask["ask_id"], "yes": True}, headers=HEADERS)
    _wait_for(client, sid, "live_confirm_unclear")
    _wait_for(client, sid, "recommendation")  # the usual flow takes over
    types = [e["type"] for e in client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]]
    assert "booking_status" not in types
