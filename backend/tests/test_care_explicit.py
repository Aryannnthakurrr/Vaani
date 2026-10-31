"""Outright requests skip the intake questions: "send an ambulance now" dispatches, a named specialist books."""

from __future__ import annotations

import pytest

from app.care.triage import explicit_request
from tests.conftest import HEADERS


@pytest.mark.parametrize(
    ("text", "kind"),
    [
        ("I want an ambulance now", "ambulance"),
        ("abhi ambulance bhejo", "ambulance"),
        ("एम्बुलेंस बुलाओ जल्दी", "ambulance"),
        ("ambulance mat bhejo", None),
        ("I want an orthopedic number, book appointment from hospital", "appointment"),
        ("mujhe cardiologist ka appointment chahiye", "appointment"),
        ("my knee hurts since yesterday", None),
    ],
)
def test_explicit_request(text: str, kind: str | None) -> None:
    assert explicit_request(text) == kind


def _intake(client, text: str) -> dict:
    sid = client.post("/care/session", json={"location": {"lat": 28.46, "lng": 77.03}, "reply_style": "en"},
                      headers=HEADERS).json()["session_id"]  # fmt: skip
    return client.post(f"/care/{sid}/intake", json={"text": text}, headers=HEADERS).json()


def test_ambulance_request_goes_straight_to_path_a(client) -> None:
    out = _intake(client, "I want an ambulance now")
    assert out["done"] and out["route"] == "A" and out["explicit"] == "ambulance"


def test_specialist_booking_goes_straight_to_path_b(client) -> None:
    out = _intake(client, "I want an orthopedic number, book appointment from hospital")
    assert out["done"] and out["route"] == "B" and out["explicit"] == "appointment"
    assert out["note"]["specialty"] == "orthopedics"


def test_red_flag_still_wins_over_a_booking(client) -> None:
    out = _intake(client, "book a cardiologist appointment, I have crushing chest pain and sweating")
    assert out["route"] == "A" and out["explicit"] is None


def test_ambulance_request_mid_intake_dispatches(client) -> None:
    sid = client.post("/care/session", json={"location": {"lat": 28.46, "lng": 77.03}, "reply_style": "en"},
                      headers=HEADERS).json()["session_id"]  # fmt: skip
    client.post(f"/care/{sid}/intake", json={"text": "acha wo"}, headers=HEADERS)
    out = client.post(f"/care/{sid}/intake", json={"text": "just send an ambulance now"}, headers=HEADERS).json()
    assert out["done"] and out["route"] == "A" and out["explicit"] == "ambulance"
