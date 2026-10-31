"""Hindi-script call text shown to English and Hinglish users in Roman letters."""

from __future__ import annotations

import asyncio

import pytest

from app.speech import romanize


@pytest.fixture()
def fake_sarvam(monkeypatch):  # noqa: ANN001, ANN201
    monkeypatch.setenv("SARVAM_API_KEY", "sk-test")
    from app.config import get_settings

    get_settings.cache_clear()
    table = {"डॉक्टर अनेश": "Doctor Anesh", "कल सुबह 9 बजे": "Kal subah 9 baje"}
    monkeypatch.setattr(romanize, "_sync", lambda text, key: table.get(text, text))
    yield
    get_settings.cache_clear()


def test_hospital_text_becomes_roman(fake_sarvam) -> None:  # noqa: ANN001
    data = {"hospital_id": "places/1", "doctor": "डॉक्टर अनेश", "slot": "कल सुबह 9 बजे", "fee": "500",
            "lines": [{"speaker": "hospital", "text": "कल सुबह 9 बजे"}]}  # fmt: skip
    out = asyncio.run(romanize.romanize_data(data, "hinglish"))
    assert out == {"hospital_id": "places/1", "doctor": "Doctor Anesh", "slot": "Kal subah 9 baje", "fee": "500",
                   "lines": [{"speaker": "hospital", "text": "Kal subah 9 baje"}]}  # fmt: skip


def test_hindi_users_keep_devanagari(fake_sarvam) -> None:  # noqa: ANN001
    assert asyncio.run(romanize.romanize_data({"doctor": "डॉक्टर अनेश"}, "hi")) == {"doctor": "डॉक्टर अनेश"}
    assert asyncio.run(romanize.romanize("Dr. Anesh, kal 9 baje", "en")) == "Dr. Anesh, kal 9 baje"
