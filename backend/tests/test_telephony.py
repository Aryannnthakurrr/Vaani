"""Real clinic calls (Bolna only): number handling, the allowlist and the not-configured answer.

The Bolna call itself (start → poll → summary) is covered in test_bolna.py.
"""

from __future__ import annotations

import pytest

from app.telephony.calls import allowed_numbers, normalize_number

H = {"X-Saathi-Device": "test-device-123"}
APPT = {"doctor": "Dr. Anil Mehta", "clinic": "Mehta Heart Clinic", "start_iso": "2026-11-01T16:00:00"}


def test_normalize_indian_numbers() -> None:
    for raw in ("7065220661", "07065220661", "+91 70652 20661", "+917065220661"):
        assert normalize_number(raw) == "+917065220661"
    assert normalize_number("") == ""


def test_allowed_numbers_keep_their_order(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "9800000001, +91 70652 20661,")
    assert allowed_numbers() == ["+919800000001", "+917065220661"]


def test_start_needs_bolna(client) -> None:  # noqa: ANN001
    r = client.post("/calls/clinic/real/start", json={"appointment": APPT}, headers=H)
    assert r.status_code == 503 and "BOLNA" in r.json()["detail"]


def test_unlisted_numbers_are_refused(client, monkeypatch: pytest.MonkeyPatch) -> None:  # noqa: ANN001
    monkeypatch.setenv("BOLNA_API_KEY", "bn-test")
    monkeypatch.setenv("BOLNA_AGENT_ID", "agent-1")
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "7065220661")
    called: list[str] = []
    monkeypatch.setattr(
        "app.routers.telephony.bolna_place_call", lambda cfg, to, data: called.append(to) or "ex1"
    )
    body = {"appointment": APPT, "to": "9999999999"}
    assert client.post("/calls/clinic/real/start", json=body, headers=H).status_code == 403
    assert called == []  # nothing was dialled
