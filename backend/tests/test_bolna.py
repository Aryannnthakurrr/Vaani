"""Real clinic calls through Bolna: start, poll a scripted execution to completion, allow-list and no-answer."""

from __future__ import annotations

import pytest

H = {"X-Saathi-Device": "test-device-123"}
APPT = {"doctor": "Dr. Anil Mehta", "clinic": "Mehta Heart Clinic", "start_iso": "2026-11-01T16:00:00"}


@pytest.fixture()
def bolna(client, monkeypatch: pytest.MonkeyPatch):  # noqa: ANN001, ANN201
    monkeypatch.setenv("BOLNA_API_KEY", "bn-test")
    monkeypatch.setenv("BOLNA_AGENT_ID", "agent-1")
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "7065220661")
    placed: list[dict] = []
    executions: list[dict] = []

    def fake_place(cfg, to, user_data):  # noqa: ANN001, ANN202
        placed.append({"to": to, **user_data})
        return "exec-1"

    monkeypatch.setattr("app.routers.telephony.bolna_place_call", fake_place)
    monkeypatch.setattr("app.routers.telephony.get_execution", lambda cfg, eid: executions.pop(0))
    return client, placed, executions


def test_bolna_call_polls_to_a_summary(bolna) -> None:  # noqa: ANN001
    client, placed, executions = bolna
    r = client.post(
        "/calls/clinic/real/start",
        json={"patient_name": "Kamla", "appointment": APPT, "reply_style": "hinglish"},
        headers=H,
    )
    assert r.status_code == 200, r.text
    session = r.json()
    assert session["status"] == "queued"
    assert placed == [
        {
            "to": "+917065220661",
            "patient_name": "Kamla",
            "doctor": "Dr. Anil Mehta",
            "clinic": "Mehta Heart Clinic",
            "appointment_time": "1 November at 4:00 PM",
            "task": "Confirm the appointment of Kamla with Dr. Anil Mehta at Mehta Heart Clinic on "
            "1 November at 4:00 PM, then ask for the current waiting time.",
        }
    ]

    executions.append(
        {
            "status": "in-progress",
            "transcript": "assistant: Namaste, main Saathi.\nuser: Haan ji, confirmed hai.\n",
        }
    )
    live = client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()
    assert not live["done"] and [x["role"] for x in live["transcript"]] == ["agent", "clinic"]

    executions.append(
        {
            "status": "completed",
            "transcript": "assistant: Namaste.\nuser: Haan ji, confirmed hai.\n"
            "assistant: Wait kitna hai?\nuser: Aadha ghanta lagega.\nassistant: Dhanyavaad.\n",
        }
    )
    done = client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()
    assert done["done"] and done["confirmed"] is True and done["waiting_minutes"] == 30
    assert done["summary"].startswith("Maine clinic ko phone kiya") and "30 minute" in done["summary"]
    # Finished calls are not polled again.
    assert client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()["done"]


def test_bolna_no_answer_and_allow_list(bolna) -> None:  # noqa: ANN001
    client, _, executions = bolna
    session = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H).json()
    executions.append({"status": "no-answer", "transcript": ""})
    state = client.get(f"/calls/clinic/real/{session['id']}", headers=H).json()
    assert state["done"] and "picked up" in state["summary"]
    other = client.post("/calls/clinic/real/start", json={"appointment": APPT, "to": "9999999999"}, headers=H)
    assert other.status_code == 403


BOOK = {
    "purpose": "book",
    "reason": "kalai ki fracture ki aftercare",
    "needs": ["X-ray", "orthopaedic doctor"],
    "window": "kal subah",
    "reply_style": "hinglish",
    "place": {"name": "City Hospital", "facilities": ["X-ray", "ultrasound"]},
}


def test_booking_call_needs_a_yes_a_name_and_a_reason(bolna) -> None:  # noqa: ANN001
    client, placed, _ = bolna
    start = lambda body: client.post("/calls/clinic/real/start", json=body, headers=H)  # noqa: E731
    assert start(BOOK).status_code == 409  # no spoken or tapped yes yet
    assert start({**BOOK, "user_confirmed": True}).status_code == 422  # nobody has told Saathi a name
    assert start({**BOOK, "user_confirmed": True, "patient_name": "Kamla", "reason": None}).status_code == 422
    assert placed == []


def test_booking_call_tells_the_clinic_who_and_why(bolna) -> None:  # noqa: ANN001
    import asyncio

    from app.memory import get_memory

    client, placed, executions = bolna
    # The app sends no name; the one the elder told Saathi is used. Their stored health facts never are.
    asyncio.run(get_memory().remember("test-device-123", "Kamla Devi", "name", "name"))
    asyncio.run(get_memory().remember("test-device-123", "Sugar ki dawai leti hain", "health", "diabetes"))
    r = client.post("/calls/clinic/real/start", json={**BOOK, "user_confirmed": True}, headers=H)
    assert r.status_code == 200, r.text
    sent = placed[0]
    assert sent["patient_name"] == "Kamla Devi" and sent["clinic"] == "City Hospital"
    task = sent["task"]
    assert "Book an appointment for Kamla Devi" in task and "kalai ki fracture ki aftercare" in task
    assert "X-ray, orthopaedic doctor" in task and "within kal subah" in task and "ultrasound" in task
    assert "Sugar" not in str(sent)  # health facts stay with the elder's own assistant

    executions.append(
        {
            "status": "completed",
            "transcript": "assistant: Namaste.\nuser: Haan ji, X-ray hai.\nassistant: Kab aa sakti hain?\n"
            "user: Kal subah 11 baje aa jaiye.\nassistant: Dhanyavaad.\n",
        }
    )
    done = client.get(f"/calls/clinic/real/{r.json()['id']}", headers=H).json()
    assert done["done"] and done["purpose"] == "book"
    assert done["summary"] == "Maine City Hospital ko phone kiya. Unhone kaha: Kal subah 11 baje aa jaiye."


def test_confirm_call_uses_the_stored_name_instead_of_the_patient(bolna) -> None:  # noqa: ANN001
    import asyncio

    from app.memory import get_memory

    client, placed, _ = bolna
    asyncio.run(get_memory().remember("test-device-123", "Sharma ji", "name", "name"))
    body = {"patient_name": "the patient", "appointment": APPT}
    assert client.post("/calls/clinic/real/start", json=body, headers=H).status_code == 200
    assert placed[0]["patient_name"] == "Sharma ji"
