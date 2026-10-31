"""Routing fixes from phone testing on 2026-10-31: "help chahiye" means a house helper; sending a photo is guided."""

import pytest

from app.llm.rules_intent import detect_intent


@pytest.mark.parametrize("text", ["mujhe help chahiye", "kal ke liye helper chahiye", "ghar ke kaam ke liye koi chahiye"])
def test_help_chahiye_is_house_help(text: str) -> None:
    assert detect_intent(text) == "book_house_help"


@pytest.mark.parametrize("text", ["send this photo to Aarav", "ye photo Aarav ko bhej do", "share this with Riya"])
def test_sending_a_photo_is_guided(text: str) -> None:
    assert detect_intent(text) == "guide"
