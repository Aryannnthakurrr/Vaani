"""Simulated clinic call: the scripted conversation, waiting-time parsing and the HTTP route."""

from __future__ import annotations

from app.llm.clinic_call import (
    CallLine,
    ClinicCallRequest,
    ClinicPlace,
    booking_summary,
    mock_turn,
    task_brief,
    waiting_minutes,
)
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


def _book(*lines: tuple[str, str], style: str = "en") -> ClinicCallRequest:
    return ClinicCallRequest(
        patient_name="Kamla Devi",
        reply_style=style,
        purpose="book",
        reason="kalai ki fracture ki aftercare",
        needs=["X-ray"],
        window="kal subah",
        place=ClinicPlace(name="City Hospital", facilities=["X-ray"]),
        transcript=[CallLine(role=r, text=t) for r, t in lines],  # type: ignore[arg-type]
    )


def test_booking_call_states_name_reason_and_needs_in_every_style() -> None:
    first = {s: mock_turn(_book(style=s)).say for s in ("en", "hi", "hinglish")}
    assert "Kamla Devi" in first["en"] and "kalai ki fracture" in first["en"] and "X-ray" in first["en"]
    assert first["hi"].startswith("नमस्ते") and "Kamla Devi" in first["hi"] and "X-ray" in first["hi"]
    assert "Kya aapke yahan X-ray hai?" in first["hinglish"]


def test_booking_call_books_a_slot_or_stops_without_the_facility() -> None:
    lines = [("agent", "…"), ("clinic", "Haan ji, X-ray hai")]
    ask = mock_turn(_book(*lines, style="hinglish"))
    assert ask.has_facilities is True and "kal subah" in ask.say and not ask.done
    booked = mock_turn(_book(*lines, ("agent", ask.say), ("clinic", "Kal 11 baje."), style="hi"))
    assert booked.done and booked.slot == "Kal 11 baje" and booked.has_facilities is True
    assert booked.summary == booking_summary(_book(style="hi"), True, "Kal 11 baje")
    assert "City Hospital" in booked.summary and "Kal 11 baje" in booked.summary

    no = mock_turn(_book(("agent", "…"), ("clinic", "Nahi, X-ray nahi hai"), style="en"))
    assert no.done and no.has_facilities is False and "Shall I try another place?" in (no.summary or "")


def test_task_brief_is_what_the_call_model_works_from() -> None:
    brief = task_brief(_book())
    assert brief.startswith("Book an appointment for Kamla Devi at City Hospital.")
    assert "First ask whether they have: X-ray." in brief and "Accept only a time inside" in brief
    assert task_brief(_req()).startswith("Confirm the appointment of Kamla with Dr. Anil Mehta")
