"""Symptom-to-care: safety net, routing (workflow §3.6 acceptance), extraction, ranking and Path B end to end."""

from __future__ import annotations

import asyncio
import time
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.care import calls, extract, orchestrator, ranking, safety_net
from app.care.calls import CallRun
from app.care.models import CallResult, Hospital, Slot
from tests.conftest import HEADERS

IST = ZoneInfo("Asia/Kolkata")

# §3.6 acceptance: 15 scripted symptom sets. Red flags → A, everyday complaints → B.
RED_FLAG_CASES = [
    ("My father has chest pain going to his left arm and he is sweating", "cardiac_pattern"),
    ("Papa ko seene mein dard hai aur bahut paseena aa raha hai", "cardiac_pattern"),
    ("She passed out in the bathroom and is not responding", "unresponsive"),
    ("He can't breathe and his lips are blue", "not_breathing"),
    ("Her face is drooping and her speech is slurred", "stroke"),
    ("There is a deep cut and it won't stop bleeding", "severe_bleeding"),
    ("Dadi ko daura pad raha hai, haath pair kaanp rahe hain", "seizure"),
    ("He fell from the roof and hit his head", "major_trauma"),
    ("My throat is swelling and I can't swallow after eating peanuts", "anaphylaxis"),
    ("Mummy ne galti se keetnashak pi liya", "poisoning"),
    ("I don't want to live anymore", "self_harm"),
]
EVERYDAY_CASES = [
    "I have had a fever and body ache since yesterday",
    "There is an itchy rash on my arm",
    "Mild back pain after lifting a bag, no other problem",
    "Halki khaansi hai do din se",
]


@pytest.mark.parametrize(("text", "flag"), RED_FLAG_CASES)
def test_safety_net_flags(text: str, flag: str) -> None:
    assert flag in safety_net.scan(text)


@pytest.mark.parametrize("text", EVERYDAY_CASES)
def test_safety_net_quiet_on_everyday_complaints(text: str) -> None:
    assert safety_net.scan(text) == []


def test_negations_do_not_trigger() -> None:
    assert safety_net.scan("Chest pain when I cough, but no sweating and no arm pain") == []
    assert safety_net.scan("seene mein dard hai, paseena nahi hai") == []


def test_follow_up_answers() -> None:
    assert safety_net.scan_answer("Is the person awake and able to talk to you normally?", "nahi") == [
        "unresponsive"
    ]
    assert safety_net.scan_answer("Is the person awake and able to talk to you normally?", "haan") == []
    assert safety_net.scan_answer("Are they breathing normally?", "No") == ["not_breathing"]
    assert safety_net.scan_answer("Is there any face drooping, arm weakness or slurred speech?", "yes") == [
        "stroke"
    ]


def _start(client, **extra):  # type: ignore[no-untyped-def]
    body = {"location": {"lat": 28.46, "lng": 77.03, "address": "Sector 18"}, "reply_style": "en", **extra}
    return client.post("/care/session", json=body, headers=HEADERS).json()["session_id"]


def _say(client, sid: str, text: str) -> dict:  # type: ignore[no-untyped-def]
    return client.post(f"/care/{sid}/intake", json={"text": text}, headers=HEADERS).json()


def _calm_answer(question: str) -> str:
    """A reassuring answer: awake/breathing normally → yes, red-flag signs and the emergency check → no."""
    q = question.lower()
    if "0 to 10" in q:
        return "since two days, about 3"
    return "yes" if ("awake" in q or "normally" in q) else "no"


@pytest.mark.parametrize(("text", "flag"), RED_FLAG_CASES)
def test_red_flags_route_to_emergency_at_once(client, text: str, flag: str) -> None:
    out = _say(client, _start(client), text)
    assert out["done"] is True  # no follow-up questions once a red flag is heard
    assert out["route"] == "A"
    assert flag in out["safety_flags"]
    assert "EMERGENCY ASSESSMENT" in out["note"]["note_text"]
    assert out["note"]["emergency"]["answer"] == "YES"


@pytest.mark.parametrize("text", EVERYDAY_CASES)
def test_everyday_complaints_route_to_care(client, text: str) -> None:
    sid = _start(client)
    out = _say(client, sid, text)
    turns = 0
    while not out["done"]:
        out = _say(client, sid, _calm_answer(out["question"]))
        turns += 1
        assert turns <= 3
    assert out["route"] == "B"
    assert "EMERGENCY ASSESSMENT" in out["note"]["note_text"]


def test_red_flag_in_an_answer_stops_the_questions(client) -> None:
    sid = _start(client)
    first = _say(client, sid, "My grandfather feels dizzy and weak")
    assert first["done"] is False
    out = _say(client, sid, "yes, his face is drooping")
    assert out["done"] is True and out["route"] == "A"


def test_events_are_device_scoped(client) -> None:
    sid = _start(client)
    _say(client, sid, "fever since yesterday")
    events = client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]
    assert [e["type"] for e in events][:2] == ["session_started", "intake_question"]
    other = {"X-Saathi-Device": "someone-else-0002"}
    assert client.get(f"/care/{sid}/events", headers=other).status_code == 404


def test_parse_slots() -> None:
    now = datetime(2026, 10, 31, 14, 0, tzinfo=IST)  # a Saturday
    slots = extract.parse_slots("Kal 11 baje aur 4:30 pm pe slot hai", now)
    assert [s.start[:16] for s in slots] == ["2026-11-01T11:00", "2026-11-01T16:30"]
    assert extract.parse_slots("Monday 10 am", now)[0].start.startswith("2026-11-02T10:00")
    assert extract.parse_slots("we have 2 doctors", now) == []


def test_rule_booking() -> None:
    wanted = Slot(start="2026-11-01T11:00:00+05:30")
    ok = extract.rule_booking([{"role": "clinic", "text": "Done, booking number is CC-2291"}], wanted)
    assert ok.status == "confirmed" and ok.booking_ref == "CC-2291"
    gone = extract.rule_booking(
        [{"role": "clinic", "text": "11 is already booked, 12:30 pm is free"}], wanted
    )
    assert gone.status == "slot_unavailable" and gone.alternatives


def test_filter_and_rank() -> None:
    hs = [
        Hospital(
            id="a", name="City Heart Hospital", phone="+911", rating=4.5, rating_count=900, eta_minutes=10
        ),
        Hospital(id="b", name="Smile Dental Care", phone="+912", rating=4.9, rating_count=500, eta_minutes=3),
        Hospital(id="c", name="No Phone Hospital", rating=4.0, rating_count=10, eta_minutes=5),
        Hospital(id="d", name="Closed Clinic", phone="+913", open_now=False, eta_minutes=4),
        Hospital(
            id="e", name="Far General Hospital", phone="+914", rating=4.0, rating_count=50, eta_minutes=40
        ),
    ]
    kept = ranking.filter_candidates(hs, "cardiology")
    assert [h.id for h in kept] == ["a", "e"]
    for h in kept:
        h.capability = ranking.capability_guess(h, "cardiology")
    assert ranking.rank(kept)[0].id == "a"


def test_rerank_prefers_confirmed_earliest() -> None:
    early = Slot(start="2026-11-01T10:00:00+05:30")
    late = Slot(start="2026-11-02T10:00:00+05:30")
    r1 = CallResult(hospital_id="x", hospital_name="X", specialty_available=True, slots=[late], eta_minutes=5)
    r2 = CallResult(
        hospital_id="y", hospital_name="Y", specialty_available=True, slots=[early], eta_minutes=20
    )
    r3 = CallResult(hospital_id="z", hospital_name="Z", specialty_available=False, slots=[early])
    assert [r.hospital_id for r in ranking.rerank([r1, r2, r3], {})] == ["y", "x"]


def test_path_b_end_to_end_with_fake_calls(client, monkeypatch) -> None:
    """Demo hospitals on three different numbers: parallel calls (one says no, one never answers), then a booking."""
    monkeypatch.setenv("DEMO_MODE", "1")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    monkeypatch.setenv("DEMO_PHONE_MAP", "+917000000001,+917000000002,+917000000003")
    monkeypatch.setattr(calls, "calls_ready", lambda: True)
    scripts = {
        "demo/1": ("COMPLETED", "Haan, Dr. Meera Iyer hai, kal 11 baje slot hai, fee 600 rupees"),
        "demo/2": ("COMPLETED", "No, cardiology doctor is not available this week"),
        "demo/3": ("NO_ANSWER", ""),
    }

    async def fake_run(session, hospital_id, number, mode, user_data):  # type: ignore[no-untyped-def]
        if mode == "booking":
            return CallRun(
                "bk1", "COMPLETED", [{"role": "clinic", "text": "Confirmed, token number is CC-2291"}]
            )
        outcome, said = scripts.get(hospital_id, ("NO_ANSWER", ""))
        return CallRun(f"ex-{hospital_id}", outcome, [{"role": "clinic", "text": said}] if said else [])

    monkeypatch.setattr(calls, "run_call", fake_run)
    sid = _start(client)
    out = _say(client, sid, "Mild chest discomfort when climbing stairs, no sweating")
    while not out["done"]:
        out = _say(client, sid, _calm_answer(out["question"]))
    assert out["route"] == "B"
    assert client.post(f"/care/{sid}/search", headers=HEADERS).json() == {"started": True}

    def events() -> list[dict]:
        return client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]

    def wait_for(kind: str) -> dict:
        for _ in range(100):
            found = [e for e in events() if e["type"] == kind]
            if found:
                return found[-1]["data"]
            time.sleep(0.02)
        raise AssertionError(f"no {kind} event; got {[e['type'] for e in events()]}")

    rec = wait_for("recommendation")
    assert rec["primary"]["doctor_name"] == "Dr. Meera Iyer"
    assert "Should I book" in rec["spoken_text"]
    outcomes = {
        e["data"]["hospital_id"]: e["data"]["call_outcome"] for e in events() if e["type"] == "call_result"
    }
    assert outcomes == {"demo/1": "COMPLETED", "demo/2": "COMPLETED", "demo/3": "NO_ANSWER"}
    booked = client.post(
        f"/care/{sid}/book",
        json={"hospital_id": rec["primary"]["hospital_id"], "slot": rec["slot"]},
        headers=HEADERS,
    ).json()
    assert booked == {"started": True}
    for _ in range(100):
        statuses = [e["data"] for e in events() if e["type"] == "booking_status"]
        if statuses and statuses[-1]["status"] != "calling":
            break
        time.sleep(0.02)
    final = statuses[-1]
    assert final["status"] == "confirmed" and final["booking_ref"] == "CC-2291"
    assert final["calendar"]["title"].startswith("Dr. Meera Iyer")
    assert orchestrator.human_time(rec["slot"]["start"], "en")


def test_demo_channel_calls_one_phone_in_turn(client, monkeypatch) -> None:
    """No Maps key, no location, no DEMO_PHONE_MAP: three fitting hospitals all ring the default number, one at a time."""
    monkeypatch.setenv("DEMO_MODE", "1")
    monkeypatch.setenv("GOOGLE_MAPS_API_KEY", "")
    monkeypatch.setenv("DEMO_PHONE_MAP", "")
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "7065220661,+919800000000")
    monkeypatch.setattr(calls, "calls_ready", lambda: True)
    live: list[str] = []
    dialled: list[tuple[str, str | None]] = []

    async def fake_run(session, hospital_id, number, mode, user_data):  # type: ignore[no-untyped-def]
        assert not live, "a second call started while the phone was busy"
        live.append(hospital_id)
        dialled.append((hospital_id, number))
        await asyncio.sleep(0.01)
        live.pop()
        return CallRun(
            f"ex-{hospital_id}",
            "COMPLETED",
            [{"role": "clinic", "text": "Haan, kal 11 baje, ECG bhi hota hai"}],
        )

    monkeypatch.setattr(calls, "run_call", fake_run)
    sid = client.post("/care/session", json={"reply_style": "en"}, headers=HEADERS).json()["session_id"]
    out = _say(client, sid, "Chest pain when I climb stairs, no sweating")
    while not out["done"]:
        out = _say(client, sid, _calm_answer(out["question"]))
    assert client.post(f"/care/{sid}/search", headers=HEADERS).status_code == 200
    for _ in range(200):
        types = [e["type"] for e in client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]]
        if "recommendation" in types:
            break
        time.sleep(0.02)
    found = next(
        e
        for e in client.get(f"/care/{sid}/events", headers=HEADERS).json()["events"]
        if e["type"] == "hospitals_found"
    )
    names = [h["name"] for h in found["data"]["hospitals"]]
    assert len(names) == 3 and any("Heart" in n for n in names)
    assert [n for _, n in dialled] == ["+917065220661"] * 3


def test_real_hospitals_are_masked_to_the_test_number(monkeypatch) -> None:
    monkeypatch.setenv("CALL_ALLOWED_NUMBERS", "+919800000001")
    monkeypatch.setenv("DEMO_PHONE_MAP", "")
    real = [
        Hospital(id=f"places/{i}", name=f"Real Hospital {i}", phone=f"+91 11 4000 000{i}") for i in range(3)
    ]
    monkeypatch.delenv("MASK_HOSPITAL_NUMBERS", raising=False)  # masking is on unless switched off
    ranking.assign_call_numbers(real, ranking.mask_numbers())
    assert [h.call_phone for h in real] == ["+919800000001"] * 3
    assert real[0].phone == "+91 11 4000 0000"  # what the screen shows stays real
    monkeypatch.setenv("MASK_HOSPITAL_NUMBERS", "0")
    ranking.assign_call_numbers(real, ranking.mask_numbers())
    assert real[0].call_phone == "+911140000000" and not calls.allowed(real[0].call_phone)
