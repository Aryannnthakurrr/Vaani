"""Simulated clinic call (SPEC §7.7 stretch): Saathi phones the clinic on the patient's behalf.

Without telephony, a teammate types the clinic's side; each turn the backend returns Vaani's next line.
This module holds the schemas and the deterministic mock conversation, which is also the fallback when
Gemini fails.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, Field

from app.schemas import Appointment

Role = Literal["agent", "clinic"]


class CallLine(BaseModel):
    role: Role
    text: str = Field(max_length=500)


class ClinicPlace(BaseModel):
    """A clinic or hospital to call. Filled from the places search (a later commit); every field is optional."""

    name: str | None = Field(default=None, max_length=120)
    phone: str | None = Field(default=None, max_length=20)
    address: str | None = Field(default=None, max_length=200)
    facilities: list[str] = Field(default_factory=list, max_length=20)  # what the listing says it has
    notes: str | None = Field(default=None, max_length=300)


class ClinicCallRequest(BaseModel):
    """`confirm` checks an existing appointment; `book` asks for one, saying why the patient needs to come.

    The visit reason and needed facilities are said for this call only ("aftercare for a wrist fracture",
    ["X-ray", "orthopaedic doctor"]) and are the only health details the call may share. They come from the
    patient for this request, never from stored health facts (app/memory AUDIENCES).
    """

    patient_name: str = Field(default="the patient", max_length=60)
    appointment: Appointment = Field(default_factory=Appointment)
    reply_style: Literal["en", "hi", "hinglish"] = "en"
    transcript: list[CallLine] = Field(default_factory=list, max_length=30)
    purpose: Literal["confirm", "book"] = "confirm"
    reason: str | None = Field(default=None, max_length=200)  # in any language, as the patient put it
    needs: list[Annotated[str, Field(max_length=40)]] = Field(default_factory=list, max_length=8)
    window: str | None = Field(
        default=None, max_length=80
    )  # when they can come: "tomorrow morning", "kal subah"
    place: ClinicPlace | None = None


class ClinicCallTurn(BaseModel):
    say: str
    done: bool = False
    confirmed: bool | None = None
    waiting_minutes: int | None = None
    summary: str | None = None
    has_facilities: bool | None = None  # book: whether the clinic can handle what the patient needs
    slot: str | None = None  # book: the time the clinic offered, as they said it


def clinic_name(req: ClinicCallRequest) -> str:
    return (req.place.name if req.place else None) or req.appointment.clinic or "the clinic"


def task_brief(req: ClinicCallRequest) -> str:
    """What this call is for, for the call's model (Gemini or Bolna). English: the model mirrors the clinic."""
    if req.purpose == "confirm":
        appt = req.appointment
        return (
            f"Confirm the appointment of {req.patient_name} with {appt.doctor or 'the doctor'} at "
            f"{clinic_name(req)} on {when(appt)}, then ask for the current waiting time."
        )
    lines = [f"Book an appointment for {req.patient_name} at {clinic_name(req)}."]
    if req.reason:
        lines.append(f"Reason for the visit, which you may share: {req.reason}.")
    if req.needs:
        lines.append(
            f"First ask whether they have: {', '.join(req.needs)}. If not, thank them and end the call."
        )
    if req.place and req.place.facilities:
        lines.append(f"Their listing mentions: {', '.join(req.place.facilities)} (confirm, don't assume).")
    lines.append(
        f"Ask for the earliest time {('within ' + req.window) if req.window else 'they can offer'}. Accept only a "
        "time inside that window; otherwise say you will check with the patient and end the call."
    )
    return " ".join(lines)


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


CALL_NOTES: dict[str, dict[str, str]] = {
    "busy": {"en": "The clinic's line was busy. Please try again in a few minutes.",
             "hi": "क्लिनिक का फ़ोन व्यस्त था। कुछ मिनट बाद फिर कोशिश कीजिए।",
             "hinglish": "Clinic ka phone busy tha. Kuch minute baad phir try kijiye."},
    "no-answer": {"en": "Nobody at the clinic picked up. Please try again later.",
                  "hi": "क्लिनिक पर किसी ने फ़ोन नहीं उठाया। बाद में फिर कोशिश कीजिए।",
                  "hinglish": "Clinic par kisi ne phone nahi uthaya. Baad mein phir try kijiye."},
    "not_connected": {"en": "The call could not be connected.", "hi": "फ़ोन नहीं लग पाया।",
                      "hinglish": "Phone nahi lag paaya."},
    "silent": {"en": "Nobody at the clinic spoke on the call. Please try again later.",
               "hi": "फ़ोन पर क्लिनिक से कोई नहीं बोला। बाद में फिर कोशिश कीजिए।",
               "hinglish": "Phone par clinic se koi nahi bola. Baad mein phir try kijiye."},
    "not_heard": {"en": "I called the clinic but could not hear them clearly. Please try again later.",
                  "hi": "मैंने क्लिनिक को फ़ोन किया, पर उनकी बात साफ़ सुनाई नहीं दी। बाद में फिर कोशिश कीजिए।",
                  "hinglish": "Maine clinic ko phone kiya, par unki baat saaf sunai nahi di. Baad mein phir try kijiye."},
    "user_ended": {"en": "You ended the call before it was finished.",
                   "hi": "आपने बात पूरी होने से पहले फ़ोन काट दिया।",
                   "hinglish": "Aapne baat poori hone se pehle call kaat di."},
    "ended_early": {"en": "The call ended before it was finished.", "hi": "बात पूरी होने से पहले फ़ोन कट गया।",
                    "hinglish": "Baat poori hone se pehle call kat gayi."},
    "clinic_said": {"en": "I called {place}. They said: {said}",
                    "hi": "मैंने {place} को फ़ोन किया। उन्होंने कहा: {said}",
                    "hinglish": "Maine {place} ko phone kiya. Unhone kaha: {said}"},
}  # fmt: skip


def call_note(kind: str, style: str, **values: str) -> str:
    text = CALL_NOTES.get(kind, CALL_NOTES["not_connected"]).get(style) or CALL_NOTES[kind]["en"]
    return text.format(**values) if values else text


def booking_summary(req: ClinicCallRequest, has_facilities: bool | None, slot: str | None) -> str:
    """What the patient hears after a booking call, in their style."""
    place, style = clinic_name(req), req.reply_style
    if has_facilities is False:
        return {
            "en": f"I called {place}. They cannot help with this visit, so nothing is booked. Shall I try another place?",
            "hi": f"मैंने {place} को फ़ोन किया। वहाँ इस इलाज की सुविधा नहीं है, इसलिए कुछ बुक नहीं हुआ। क्या मैं कोई दूसरी जगह देखूँ?",
            "hinglish": f"Maine {place} ko phone kiya. Wahan iski facility nahi hai, isliye kuch book nahi hua. Kya main koi dusri jagah dekhun?",
        }[style]
    if slot:
        return {
            "en": f"I called {place}. They can see you at {slot}. Please check the details before you go.",
            "hi": f"मैंने {place} को फ़ोन किया। वे आपको {slot} पर देख सकते हैं। जाने से पहले एक बार पक्का कर लीजिए।",
            "hinglish": f"Maine {place} ko phone kiya. Woh aapko {slot} par dekh sakte hain. Jaane se pehle ek baar confirm kar lijiye.",
        }[style]
    return {
        "en": f"I called {place}, but we could not fix a time. Please call them or ask your family.",
        "hi": f"मैंने {place} को फ़ोन किया, पर समय तय नहीं हो पाया। कृपया उन्हें फ़ोन कीजिए या परिवार से कहिए।",
        "hinglish": f"Maine {place} ko phone kiya, par time fix nahi ho paya. Please unhe phone kijiye ya family se kahiye.",
    }[style]  # fmt: skip


def _booking_turn(req: ClinicCallRequest) -> ClinicCallTurn:
    """Script: introduce + reason + facilities → ask for a time in the window → repeat it back and hang up."""
    agent_turns = sum(1 for line in req.transcript if line.role == "agent")
    last_clinic = next((line.text for line in reversed(req.transcript) if line.role == "clinic"), "")
    name, style = req.patient_name, req.reply_style
    needs = ", ".join(req.needs)
    if agent_turns == 0:
        why = f" {name} needs {req.reason}." if req.reason else ""
        ask = f" Do you have {needs}?" if needs else " Can you help with that?"
        return ClinicCallTurn(say={
            "en": f"Namaste. I am Vaani, an AI assistant calling on behalf of {name}.{why}{ask}",
            "hi": f"नमस्ते। मैं वाणी हूँ, {name} की ओर से फ़ोन कर रही AI सहायक।"
                  + (f" उन्हें {req.reason} के लिए आना है।" if req.reason else "")
                  + (f" क्या आपके यहाँ {needs} है?" if needs else " क्या आप मदद कर सकते हैं?"),
            "hinglish": f"Namaste. Main Vaani hoon, {name} ki taraf se call kar rahi AI assistant."
                        + (f" Unhe {req.reason} ke liye aana hai." if req.reason else "")
                        + (f" Kya aapke yahan {needs} hai?" if needs else " Kya aap help kar sakte hain?"),
        }[style])  # fmt: skip
    if agent_turns == 1:
        if confirmed_from(last_clinic) is False:
            bye = {"en": "I understand. Thank you, goodbye.", "hi": "समझ गई। धन्यवाद, नमस्ते।",
                   "hinglish": "Samajh gayi. Dhanyavaad, namaste."}[style]  # fmt: skip
            return ClinicCallTurn(say=bye, done=True, has_facilities=False,
                                  summary=booking_summary(req, False, None))  # fmt: skip
        window = req.window or {"en": "soon", "hi": "जल्दी", "hinglish": "jaldi"}[style]
        return ClinicCallTurn(has_facilities=True, say={
            "en": f"Thank you. What is the earliest time you can see {name}, {window}?",
            "hi": f"धन्यवाद। {name} को सबसे जल्दी कब दिखा सकते हैं, {window}?",
            "hinglish": f"Thank you. {name} ko sabse jaldi kab dikha sakte hain, {window}?",
        }[style])  # fmt: skip
    slot = last_clinic.strip().rstrip(".!?")[:80] or None
    return ClinicCallTurn(
        say={"en": f"Thank you. So {name} will come at {slot}. Goodbye.",
             "hi": f"धन्यवाद। तो {name} {slot} पर आएँगे। नमस्ते।",
             "hinglish": f"Thank you. Toh {name} {slot} par aayenge. Namaste."}[style],
        done=True, has_facilities=True, slot=slot, summary=booking_summary(req, True, slot),
    )  # fmt: skip


def mock_turn(req: ClinicCallRequest) -> ClinicCallTurn:
    """Three-step script: introduce + confirm → ask waiting time → thank and hang up."""
    if req.purpose == "book":
        return _booking_turn(req)
    agent_turns = sum(1 for line in req.transcript if line.role == "agent")
    clinic_said = " ".join(line.text for line in req.transcript if line.role == "clinic")
    last_clinic = next((line.text for line in reversed(req.transcript) if line.role == "clinic"), "")
    doctor = req.appointment.doctor or "the doctor"
    if agent_turns == 0:
        return ClinicCallTurn(
            say=(
                f"Namaste. I am Vaani, an AI assistant calling on behalf of {req.patient_name}. "
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
