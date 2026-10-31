"""Simulated clinic call: the scripted conversation, waiting-time parsing and the HTTP route."""

from __future__ import annotations

from app.llm.clinic_call import CallLine, ClinicCallRequest, mock_turn, waiting_minutes
from app.schemas import Appointment

APPT = Appointment(doctor="Dr. Anil Mehta", clinic="Mehta Heart Clinic", start_iso="2026-10-31T16:00:00")


def _req(*lines: tuple[str, str], style: str = "en") -> ClinicCallRequest:
    return ClinicCallRequest(
        patient_name="Kamla",
        appointment=APPT,
        reply_style=style,
        transcript=[CallLine(role=r, text=t) for r, t in lines],  # type: ignore[arg-type]
    )


def test_full_happy_call() -> None:
    first = mock_turn(_req())
    assert "Saathi" in first.say and "Kamla" in first.say and "Dr. Anil Mehta" in first.say and not first.done
    second = mock_turn(_req(("agent", first.say), ("clinic", "Yes, confirmed for 4 pm.")))
    assert "waiting time" in second.say and second.confirmed is True
    third = mock_turn(
        _req(("agent", first.say), ("clinic", "Yes"), ("agent", second.say), ("clinic", "About 20 minutes"))
    )
    assert third.done and third.waiting_minutes == 20
    assert third.summary and "confirmed" in third.summary and "20 minutes" in third.summary


def test_not_confirmed_ends_call() -> None:
    first = mock_turn(_req())
    turn = mock_turn(_req(("agent", first.say), ("clinic", "No, there is no booking under that name")))
    assert turn.done and turn.confirmed is False and "call them yourself" in (turn.summary or "")


def test_hindi_summary_and_hinglish_clinic() -> None:
    first = mock_turn(_req(style="hi"))
    second = mock_turn(_req(("agent", first.say), ("clinic", "haan ji, confirm hai"), style="hi"))
    third = mock_turn(
        _req(
            ("agent", first.say),
            ("clinic", "haan ji"),
            ("agent", second.say),
            ("clinic", "aadha ghanta, 30 minute"),
            style="hi",
        )
    )
    assert third.waiting_minutes == 30
    assert third.summary and "पक्की" in third.summary


def test_waiting_minutes_parsing() -> None:
    assert waiting_minutes("roughly 45 mins") == 45
    assert waiting_minutes("an hour") == 60
    assert waiting_minutes("abhi aadha ghanta wait hai") == 30
    assert waiting_minutes("dedh ghanta lagega") == 90
    assert waiting_minutes("about half an hour") == 30
    assert waiting_minutes("2 ghante") == 120
    assert waiting_minutes("not sure") is None


def test_route_requires_device_and_runs(client) -> None:  # noqa: ANN001
    body = {"patient_name": "Kamla", "appointment": APPT.model_dump(), "transcript": []}
    assert client.post("/calls/clinic/turn", json=body).status_code == 401
    r = client.post("/calls/clinic/turn", json=body, headers={"X-Saathi-Device": "test-device-123"})
    assert r.status_code == 200
    assert "Saathi" in r.json()["say"]
