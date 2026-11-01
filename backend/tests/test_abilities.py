"""Personal compiled abilities and the camera's sugar/salt check (app/abilities)."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from app.abilities import Abilities, food, resolve_dates, user_key
from app.config import get_settings
from app.skills.sandbox import Sandbox, static_check

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


@pytest.fixture
def abilities(tmp_path: Path) -> Abilities:
    a = Abilities(FIXTURES, tmp_path / "abilities.db", Sandbox("subprocess"))
    asyncio.run(a.verify())
    return a


def test_three_personas_every_ability_passes_its_cases_in_the_sandbox(abilities: Abilities) -> None:
    assert set(abilities.personas) == {"ramesh", "sunita", "arjun"}
    for persona in abilities.personas.values():
        assert len(persona.abilities) == 2
        for a in persona.abilities:
            assert static_check(a.code, "run") == []
            assert a.status == "active", (a.name, a.last_error)
            assert any(c.get("held_out") for c in a.cases)


def test_matching_is_per_user(abilities: Abilities) -> None:
    assert abilities.match("ramesh", "aaj fasting sugar 140 aaya").name == "sugar_diary"
    assert abilities.match("sunita", "BP 146 by 92").name == "bp_log"
    assert abilities.match("arjun", "split 900 between 3").name == "bill_split"
    assert abilities.match("arjun", "aaj fasting sugar 140 aaya") is None  # Arjun never taught it that
    assert abilities.match(None, "split 900 between 3") is None
    assert abilities.match("../etc", "split 900 between 3") is None


def test_run_keeps_state_and_ids_are_plain_profile_names(abilities: Abilities, monkeypatch) -> None:
    import app.abilities as mod

    monkeypatch.setattr(mod, "_mirror", lambda *a: None)
    ability = abilities.ability("arjun", "expense_log")
    first = asyncio.run(abilities.run("arjun", ability, "I spent 240 on groceries"))
    assert "240 rupees" in first["say"]
    state, runs = abilities.state("arjun", "expense_log")
    assert runs == 1 and state["entries"][-1]["amount"] == 240
    assert user_key("arjun") == "persona:arjun"  # the same on every laptop: no device id, no hash


def test_seed_dates_follow_today() -> None:
    from datetime import date

    out = resolve_dates(
        {"readings": [{"days_ago": 2, "v": 1}], "m": {"as_of_days_ago": 1}}, date(2026, 11, 10)
    )
    assert out == {"readings": [{"date": "2026-11-08", "v": 1}], "m": {"as_of": "2026-11-09"}}


def test_food_check_for_diabetes_and_bp() -> None:
    frooti = {"name": "Frooti Mango Drink", "quantity": "200 ml"}
    lays = {"name": "Lay's Classic Salted", "quantity": "52 g"}
    level, text = food.check(frooti, {"diabetes"}, "en")
    assert level == "high" and "7 teaspoons" in text and "typically" in text
    level, text = food.check(lays, {"bp"}, "hinglish")
    assert level == "high" and "namak" in text and "0.9 gram" in text
    assert food.check(lays, {"diabetes"}, "en") is None  # chips aren't a sugar problem
    assert food.check(frooti, set(), "en") is None  # no known condition: no comment
    # A readable label wins over the table.
    label = {"name": "Unknown juice", "quantity": "1 l", "sugar_g_per_100": 3.0, "per": "ml"}
    assert food.check(label, {"diabetes"}, "en") is None
    assert food.conditions_from("User: Ramesh Sharma, 74. Health: type 2 diabetes, knee arthritis.") == {
        "diabetes"
    }
    assert food.conditions_from("Health: high blood pressure.") == {"bp"}


def test_intent_uses_the_profile_ability_first(client, monkeypatch) -> None:
    import app.abilities as mod
    from app.abilities import get_abilities

    monkeypatch.setattr(mod, "_mirror", lambda *a: None)
    monkeypatch.setenv("SKILL_SANDBOX", "subprocess")
    get_abilities.cache_clear()
    abilities = get_abilities()
    asyncio.run(abilities.verify())
    asyncio.run(abilities.reset("sunita"))
    r = client.post(
        "/intent",
        json={"utterance": "aaj BP 150 by 95 aaya"},
        headers={"X-Saathi-Profile": "sunita", "X-Saathi-Device": "demo-device-1"},
    )
    body = r.json()
    assert (
        body["intent"] == "ability"
        and "150 by 95" in body["reply_text"]
        and body["reply_style"] == "hinglish"
    )
    r = client.post(
        "/intent", json={"utterance": "aaj BP 150 by 95 aaya"}, headers={"X-Saathi-Device": "demo-device-1"}
    )
    assert r.json()["intent"] != "ability"  # without a profile the LLM answers, as before
    listing = client.get("/abilities/sunita").json()
    assert {a["name"] for a in listing["abilities"]} == {"bp_log", "salt_budget"}
    assert get_settings().fixtures_dir.exists()


def test_the_llm_does_the_talking_around_the_code_result(client, monkeypatch) -> None:
    from app.abilities import get_abilities
    from app.routers import agent

    class Talker:
        async def structured(self, route, system, prompt, schema):
            assert route == "ability_reply" and "Sunita" in system and "146 by 92" in prompt
            return schema(reply_text="Sunita ji, 146 by 92 thoda high hai; walk ke baad phir check karein?")

    get_abilities.cache_clear()
    done = {"ability": "bp_log", "say": "BP 146 by 92 high hai. Likh liya.", "urgent": False}
    reply = asyncio.run(agent.ability_reply(Talker(), "sunita", "BP 146 by 92", done, "hinglish"))
    assert reply.startswith("Sunita ji")

    class Silent:
        async def structured(self, *a):
            return None

    assert (
        asyncio.run(agent.ability_reply(Silent(), "sunita", "BP 146 by 92", done, "hinglish")) == done["say"]
    )
