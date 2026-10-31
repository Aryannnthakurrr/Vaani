"""Simulated clinic call (SPEC §7.7 stretch): Saathi phones the clinic on the patient's behalf.

Without telephony, a teammate types the clinic's side; each turn the backend returns Saathi's next line.
This module holds the schemas and the deterministic mock conversation, which is also the fallback when
Gemini fails.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.schemas import Appointment

Role = Literal["agent", "clinic"]


class CallLine(BaseModel):
    role: Role
    text: str = Field(max_length=500)


class ClinicCallRequest(BaseModel):
    patient_name: str = Field(default="the patient", max_length=60)
    appointment: Appointment
    reply_style: Literal["en", "hi", "hinglish"] = "en"
    transcript: list[CallLine] = Field(default_factory=list, max_length=30)


class ClinicCallTurn(BaseModel):
    say: str
    done: bool = False
    confirmed: bool | None = None
    waiting_minutes: int | None = None
    summary: str | None = None


_YES = re.compile(r"\b(yes|yeah|confirmed?|correct|right|haan|ha|ji|theek|sahi|bilkul)\b|हाँ|जी|ठीक", re.I)
_NO = re.compile(r"\b(no|not|cancel+ed|nahi|nahin|galat|wrong)\b|नहीं", re.I)
_MINUTES = re.compile(r"(\d{1,3})\s*(min|mins|minute|minutes|मिनट)", re.I)
_HOURS = re.compile(r"(\d{1,2}|an?|one|ek)\s*(hour|hours|hr|ghanta|ghante|घंटा|घंटे)", re.I)


def when(appt: Appointment) -> str:
    if not appt.start_iso:
        return "today"
    try:
        t = datetime.fromisoformat(appt.start_iso)
    except ValueError:
        return appt.start_iso
    return t.strftime("%d %B at %I:%M %p").lstrip("0").replace(" 0", " ")


# Spoken Hindi fractions of an hour, as receptionists say them ("aadha ghanta", "dedh ghanta").
_FRACTION_HOURS = re.compile(
    r"\b(half an hour|aadha|adha|aadhe|pauna|paune|sava|sawa|dedh|dhai|dhaai)\b|आधा|आधे|पौना|सवा|डेढ़|ढाई", re.I
)
_FRACTION_MINUTES = {
    "half an hour": 30, "aadha": 30, "adha": 30, "aadhe": 30, "आधा": 30, "आधे": 30,
    "pauna": 45, "paune": 45, "पौना": 45, "sava": 75, "sawa": 75, "सवा": 75,
    "dedh": 90, "डेढ़": 90, "dhai": 150, "dhaai": 150, "ढाई": 150,
}  # fmt: skip


def waiting_minutes(text: str) -> int | None:
    if m := _MINUTES.search(text):
        return int(m.group(1))
    if m := _FRACTION_HOURS.search(text):
        return _FRACTION_MINUTES[m.group(0).lower()]
    if m := _HOURS.search(text):
        n = m.group(1).lower()
        return 60 * (int(n) if n.isdigit() else 1)
    return None


def confirmed_from(clinic_said: str) -> bool | None:
    """True/False when the clinic clearly said yes/no; None when it said neither."""
    if _NO.search(clinic_said) and not _YES.search(clinic_said):
        return False
    return True if _YES.search(clinic_said) else None


def summary_for_user(req: ClinicCallRequest, confirmed: bool | None, wait: int | None) -> str:
    doctor = req.appointment.doctor or "the doctor"
    on = when(req.appointment)
    style = req.reply_style
    if confirmed is False:
        return {
            "en": f"The clinic could not confirm your appointment with {doctor}. Please call them yourself.",
            "hi": f"क्लिनिक ने {doctor} के साथ आपकी मुलाक़ात पक्की नहीं की। कृपया खुद फ़ोन कर लीजिए।",
            "hinglish": f"Clinic ne {doctor} ke saath appointment confirm nahi kiya. Please khud phone kar lijiye.",
        }[style]
    wait_en = f" The waiting time is about {wait} minutes." if wait is not None else ""
    wait_hi = f" अभी लगभग {wait} मिनट इंतज़ार है।" if wait is not None else ""
    wait_hn = f" Abhi lagbhag {wait} minute ka wait hai." if wait is not None else ""
    return {
        "en": f"I called the clinic. Your appointment with {doctor} on {on} is confirmed.{wait_en}",
        "hi": f"मैंने क्लिनिक को फ़ोन किया। {doctor} के साथ आपकी मुलाक़ात पक्की है।{wait_hi}",
        "hinglish": f"Maine clinic ko phone kiya. {doctor} ke saath appointment confirm hai.{wait_hn}",
    }[style]


def mock_turn(req: ClinicCallRequest) -> ClinicCallTurn:
    """Three-step script: introduce + confirm → ask waiting time → thank and hang up."""
    agent_turns = sum(1 for line in req.transcript if line.role == "agent")
    clinic_said = " ".join(line.text for line in req.transcript if line.role == "clinic")
    last_clinic = next((line.text for line in reversed(req.transcript) if line.role == "clinic"), "")
    doctor = req.appointment.doctor or "the doctor"
    if agent_turns == 0:
        return ClinicCallTurn(
            say=(
                f"Namaste. I am Saathi, an AI assistant calling on behalf of {req.patient_name}. "
                f"I would like to confirm the appointment with {doctor} on {when(req.appointment)}. Is that confirmed?"
            )
        )
    confirmed = confirmed_from(clinic_said)
    if confirmed is False:
        return ClinicCallTurn(
            say="I understand. I will ask the patient to call you directly. Thank you, goodbye.",
            done=True,
            confirmed=False,
            summary=summary_for_user(req, False, None),
        )
    if agent_turns == 1:
        return ClinicCallTurn(
            say="Thank you. How long is the waiting time at the clinic right now?", confirmed=confirmed
        )
    wait = waiting_minutes(last_clinic) or waiting_minutes(clinic_said)
    return ClinicCallTurn(
        say="Thank you very much for your help. Goodbye.",
        done=True,
        confirmed=True if confirmed is None else confirmed,
        waiting_minutes=wait,
        summary=summary_for_user(req, True, wait),
    )
