"""Intake (workflow §3.3–3.6): up to three follow-ups, then the clinical note and the route.

The LLM asks and writes the note when it can; the rule-based safety net runs on every turn regardless and can only
make a case more urgent. Without a model (mock mode, timeouts) scripted red-flag questions and a template note take
over, and with no red flag the user is asked directly whether it is an emergency (§10).
"""

from __future__ import annotations

import asyncio
import logging
import re
from datetime import datetime
from zoneinfo import ZoneInfo

from app.care import safety_net
from app.care.models import Emergency, IntakeNote, RedFlag, TriageOut
from app.care.session import MAX_FOLLOWUPS, CareSession
from app.llm.base import LLMClient
from app.llm.prompts import render

log = logging.getLogger("saathi.care.triage")
EMERGENCY_CHECK = "emergency_check"
_I = re.IGNORECASE


def _tr(style: str, en: str, hi: str, hinglish: str) -> str:
    return {"hi": hi, "hinglish": hinglish}.get(style, en)


# (keywords in what was said, question id, en, hi, hinglish). Red flags first; the generic check comes last.
_SCRIPTED = [
    (
        "chest|seen|chhati|छाती|सीने|heart|dil",
        "cardiac",
        "Is the chest pain spreading to the arm, jaw or back, or is there sweating?",
        "क्या सीने का दर्द बांह, जबड़े या पीठ तक जा रहा है, या पसीना आ रहा है?",
        "Kya seene ka dard haath, jabde ya peeth tak ja raha hai, ya paseena aa raha hai?",
    ),
    (
        "breath|saa?ns|साँस|सांस|cough|khaansi",
        "breathing",
        "Are they breathing normally?",
        "क्या साँस ठीक से चल रही है?",
        "Kya saans normal chal rahi hai?",
    ),
    (
        "dizz|chakkar|weak|kamzor|speech|bol nahi|face|confus|head|sar dard|sir dard",
        "stroke",
        "Is there any face drooping, arm weakness or slurred speech?",
        "क्या चेहरा टेढ़ा है, हाथ में कमज़ोरी है या बोलने में दिक्कत है?",
        "Kya chehra tedha hai, haath mein kamzori hai ya bolne mein dikkat hai?",
    ),
    (
        "bleed|khoon|खून|cut|injur|chot|चोट|fell|gir",
        "bleeding",
        "Is there heavy bleeding that won't stop?",
        "क्या बहुत खून बह रहा है जो रुक नहीं रहा?",
        "Kya bahut khoon beh raha hai jo ruk nahi raha?",
    ),
    (
        "",
        "conscious",
        "Is the person awake and able to talk to you normally?",
        "क्या मरीज़ होश में है और ठीक से बात कर पा रहा है?",
        "Kya patient hosh mein hai aur theek se baat kar pa raha hai?",
    ),
]
_ONSET = (
    "onset",
    "When did this start, and how bad is it from 0 to 10?",
    "यह कब शुरू हुआ, और 0 से 10 में कितना तेज़ है?",
    "Yeh kab shuru hua, aur 0 se 10 mein kitna tez hai?",
)
_IS_EMERGENCY = (
    "Do you think this is an emergency that needs an ambulance now? Please say yes or no.",
    "क्या आपको लगता है कि अभी एम्बुलेंस की ज़रूरत है? हाँ या ना बोलिए।",
    "Kya aapko lagta hai ki abhi ambulance ki zaroorat hai? Haan ya na boliye.",
)


def record_answer(session: CareSession, question: str, answer: str) -> None:
    session.followups.append({"q": question, "a": answer})
    _add_flags(session, safety_net.scan_answer(question, answer))
    _add_flags(session, safety_net.scan_with_conditions(answer, session.patient.conditions))


def record_utterance(session: CareSession, text: str) -> None:
    session.utterances.append(text)
    _add_flags(session, safety_net.scan(text))
    _add_flags(session, safety_net.scan_with_conditions(text, session.patient.conditions))


def _add_flags(session: CareSession, flags: list[str]) -> None:
    for flag in flags:
        if flag not in session.safety_flags:
            session.safety_flags.append(flag)


async def take_turn(session: CareSession, text: str, llm: LLMClient) -> dict[str, object]:
    """One intake turn (typed, turn-based or from the live conversation): the first description or the answer to
    the pending follow-up. Returns the next question, or the finished note with its route.

    A first sentence that asks for something outright ("send an ambulance now", "book an orthopedic appointment")
    skips the questions: `explicit` in the reply tells the phone to dispatch at once or start the hospital calls."""
    first = not session.pending_question and not session.utterances and not session.followups
    kind = explicit_request(text) if first else None
    if session.pending_question:
        record_answer(session, session.pending_question, text)
    else:
        record_utterance(session, text)
    if kind == "appointment" and session.safety_flags:
        kind = None  # a red flag in the same sentence still gets the full emergency check
    step = _explicit_note(session, kind, text) if kind else await next_step(session, llm)
    if isinstance(step, IntakeNote):
        session.pending_question = None
        session.emit("intake_note", note_text=step.note_text, emergency=step.emergency.model_dump(),
                     route=session.route, safety_flags=session.safety_flags)  # fmt: skip
        return {
            "done": True,
            "route": session.route,
            "note": step.model_dump(),
            "safety_flags": session.safety_flags,
            "explicit": kind,
        }
    session.pending_question = step
    session.emit("intake_question", text=step)
    return {"done": False, "question": step, "safety_flags": session.safety_flags}


_AMBULANCE = re.compile(r"ambulance|ambulence|एम्बुलेंस|ऐम्बुलेंस|एंबुलेंस", _I)
_ASKING = re.compile(
    r"\b(send|book|call|need|want|get|bhej|bula|chahiye|mangwa|abhi|now|jaldi|turant|please)|भेज|बुला|चाहिए|अभी|जल्दी|तुरंत", _I
)
_REFUSING = re.compile(r"\b(mat|nahi|nahin|don'?t|do not|no need|not)\b|मत|नहीं", _I)
_BOOKING = re.compile(
    r"\b(appointment|book|booking|number|consult|milna|dikhana|dikhaana|check ?up)\b|अपॉइंटमेंट|बुक|दिखाना|मिलना", _I
)
_DOCTOR = re.compile(
    r"ortho|cardio|neuro|derma|gyn|p(a)?ediatric|dentist|\bent\b|\beye|skin|specialist|physician|doctor|daktar|"
    r"डॉक्टर|डाक्टर|हड्डी",
    _I,
)


def explicit_request(text: str) -> str | None:
    """"ambulance" for an outright ask for one, "appointment" for a named doctor or specialist booking, else None."""
    if _AMBULANCE.search(text) and _ASKING.search(text) and not _REFUSING.search(text):
        return "ambulance"
    if _BOOKING.search(text) and _DOCTOR.search(text):
        return "appointment"
    return None


def _explicit_note(session: CareSession, kind: str, text: str) -> IntakeNote:
    """The note for an outright request: no follow-up questions, the user's own words as the complaint."""
    note = template_note(session)
    if kind == "ambulance":
        note.emergency = Emergency(answer="YES", reasoning="The user asked for an ambulance.",
                                   triggering_red_flags=note.emergency.triggering_red_flags, acuity=2)  # fmt: skip
        plan = "emergency dispatch (requested by the user)"
    else:
        note.emergency = Emergency(answer="NO", reasoning="The user asked to book an appointment; no red flag.", acuity=4)
        plan = f"book a {note.specialty} appointment (requested by the user)"
        note.appointment_timeframe = "next available"
    note.note_text = "\n".join([
        "CLINICAL INTAKE NOTE  (AI-generated. Not a diagnosis.)",
        f"Session: {session.id}            Time: {_now_iso()}",
        f"Request: {text[:200]}",
        f"Plan: {plan}",
    ])  # fmt: skip
    return _finalize(session, note)


async def next_step(session: CareSession, llm: LLMClient) -> str | IntakeNote:
    """The next follow-up question to ask, or the finished note (stored on the session with its route)."""
    if session.safety_flags:
        # A red flag starts the emergency at once with the template note; the model's fuller note replaces it in the
        # background during the countdown (it can take up to ~10 s, which must not delay the ambulance).
        note = _finalize(session, template_note(session))
        _background(_refine_emergency_note(session, llm))
        return note
    if len(session.followups) >= MAX_FOLLOWUPS:
        return await _finish(session, llm)
    out = await llm.structured("care_triage", _system(session), _transcript(session), TriageOut)
    if isinstance(out, TriageOut):
        if out.next_question and not out.note:
            return out.next_question.strip()
        if out.note:
            return _finalize(session, out.note)
    return _scripted_question(session) or await _finish(session, llm)


_tasks: set[asyncio.Task[None]] = set()


def _background(coro) -> None:  # type: ignore[no-untyped-def]
    task = asyncio.get_running_loop().create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


async def _refine_emergency_note(session: CareSession, llm: LLMClient) -> None:
    """Swaps in the model's note if it arrives before the ambulance is requested; the route stays A either way."""
    try:
        out = await llm.structured("care_triage", _system(session, questions_left=0), _transcript(session), TriageOut)
    except Exception:  # noqa: BLE001 - the template note is already good enough to dispatch
        log.info("emergency note refinement failed; keeping the template note")
        return
    if isinstance(out, TriageOut) and out.note and not session.emergency.get("dispatch_id"):
        _finalize(session, out.note)


async def _finish(session: CareSession, llm: LLMClient) -> str | IntakeNote:
    out = await llm.structured(
        "care_triage", _system(session, questions_left=0), _transcript(session), TriageOut
    )
    if isinstance(out, TriageOut) and out.note:
        return _finalize(session, out.note)
    # No model: with a red flag the template note is enough; otherwise ask the user once (§10).
    asked = any(f["q"] == _ask_text(session, EMERGENCY_CHECK) for f in session.followups)
    if not session.safety_flags and not asked:
        return _ask_text(session, EMERGENCY_CHECK)
    return _finalize(session, template_note(session))


def _ask_text(session: CareSession, qid: str) -> str:
    if qid == EMERGENCY_CHECK:
        return _tr(session.style, *_IS_EMERGENCY)
    return ""


def _scripted_question(session: CareSession) -> str | None:
    """The first scripted red-flag question that fits what was said and hasn't been asked; then onset/severity."""
    if len(session.followups) >= 2:
        return None
    said = " ".join(session.utterances)
    asked = {f["q"] for f in session.followups}
    for keywords, _qid, en, hi, hl in _SCRIPTED:
        text = _tr(session.style, en, hi, hl)
        if text not in asked and (not keywords or re.search(keywords, said, _I)):
            return text
    onset = _tr(session.style, *_ONSET[1:])
    return onset if onset not in asked else None


def _system(session: CareSession, questions_left: int | None = None) -> str:
    left = MAX_FOLLOWUPS - len(session.followups) if questions_left is None else questions_left
    loc = session.location
    return render(
        "triage",
        questions_left=str(max(left, 0)),
        reply_style=session.style,
        session_id=session.id,
        now=_now_iso(),
        address=(loc.address if loc and loc.address else "unknown"),
        landmark=(loc.landmark if loc and loc.landmark else "none"),
    )


def _transcript(session: CareSession) -> str:
    p = session.patient
    lines = [
        f"Patient profile: name={p.name or 'unknown'}, age={p.age or 'unknown'}, sex={p.sex or 'unknown'}, "
        f"known conditions={', '.join(p.conditions) or 'none given'}"
    ]
    lines += [f"User said: {u}" for u in session.utterances]
    lines += [f"Q: {f['q']}\nA: {f['a']}" for f in session.followups]
    if session.safety_flags:
        flags = ", ".join(safety_net.LABELS[f] for f in session.safety_flags)
        lines.append(f"Rule-based safety net detected: {flags}. The emergency answer must be YES.")
    return "\n".join(lines)


def _now_iso() -> str:
    return datetime.now(ZoneInfo("Asia/Kolkata")).isoformat(timespec="seconds")


def _finalize(session: CareSession, note: IntakeNote) -> IntakeNote:
    """Applies the safety net (never downgraded), fills the session id and guarantees the emergency block."""
    note.session_id = session.id
    answer = _user_emergency_answer(session)
    if answer == "YES" and note.emergency.answer == "NO":
        note.emergency.answer = "UNCERTAIN"
    if session.safety_flags:
        labels = [safety_net.LABELS[f] for f in session.safety_flags]
        note.emergency = Emergency(
            answer="YES",
            reasoning=note.emergency.reasoning or "The rule-based safety net found a red flag.",
            triggering_red_flags=sorted(set(note.emergency.triggering_red_flags) | set(labels)),
            acuity=min(note.emergency.acuity, 2),
        )
        present = {r.flag for r in note.red_flags if r.status == "present"}
        note.red_flags += [
            RedFlag(flag=f, status="present") for f in session.safety_flags if f not in present
        ]
    if "EMERGENCY ASSESSMENT" not in note.note_text:
        note.note_text = (note.note_text + "\n\n" + _emergency_block(note)).strip()
    if not note.search_queries:
        note.search_queries = _queries(note.specialty)
    session.note = note
    session.route = route_for(note, session.safety_flags)
    return note


def route_for(note: IntakeNote, safety_flags: list[str]) -> str:
    """§3.6: any safety-net hit, YES or UNCERTAIN → A (emergency); only a clear NO → B."""
    if safety_flags or note.emergency.answer in ("YES", "UNCERTAIN"):
        return "A"
    return "B"


def _user_emergency_answer(session: CareSession) -> str | None:
    question = _ask_text(session, EMERGENCY_CHECK)
    for f in session.followups:
        if f["q"] == question:
            yes = re.search(r"^\s*(?:yes|haa?n|ji|हाँ|हां)", f["a"], _I)
            return "YES" if yes else "NO"
    return None


# ------------------------------------------------------------------------------------------- template note

_SPECIALTIES = [
    ("cardiology", r"cardio|chest|heart|dil|seen|chhati|palpitat|bp|blood pressure"),
    ("neurology", r"neuro|head|sir|sar|dizz|chakkar|\bnumb|seizure|stroke|migraine"),
    ("pulmonology", r"breath|saa?ns|cough|khaansi|asthma|wheez"),
    ("gastroenterology", r"stomach|pet|vomit|ulti|diarr|loose motion|acidity|gas"),
    ("orthopedics", r"ortho|bone|haddi|हड्डी|joint|knee|ghutn|back pain|kamar|fracture|sprain"),
    ("dermatology", r"derma|skin|rash|itch|khujli|daane"),
    ("ent", r"\bent\b|\bear\b|kaan|throat|gala|nose|naak|sinus"),
    ("ophthalmology", r"\beye|aankh|vision|nazar"),
    ("gynecology", r"gyn|pregnan|period|garbh|white discharge"),
]


# A specialty the user names ("orthopedic", "cardiologist") wins over a symptom word in the same sentence.
_NAMED = [
    ("orthopedics", r"ortho"), ("cardiology", r"cardio"), ("neurology", r"neuro"), ("dermatology", r"derma"),
    ("gynecology", r"gyn"), ("ent", r"\bent\b"), ("ophthalmology", r"ophthal|eye (doctor|specialist)"),
]


def _specialty(text: str) -> str:
    return next((name for name, pattern in _NAMED + _SPECIALTIES if re.search(pattern, text, _I)), "general medicine")


def _queries(specialty: str) -> list[str]:
    if specialty == "general medicine":
        return ["multispeciality hospital", "clinic general physician"]
    return [f"{specialty} hospital", "multispeciality hospital"]


def template_note(session: CareSession) -> IntakeNote:
    """The note without a model: what was said, the safety-net result and the user's own emergency answer."""
    said = " ".join(session.utterances + [f["a"] for f in session.followups])
    flags = session.safety_flags
    answer = "YES" if flags else (_user_emergency_answer(session) or "UNCERTAIN")
    if answer == "YES" and not flags:
        answer = "UNCERTAIN"  # the user's own "yes" gets the cancellable countdown
    specialty = _specialty(said)
    cc = (session.utterances[0] if session.utterances else "unknown")[:160]
    p = session.patient
    note = IntakeNote(
        session_id=session.id,
        patient=p,
        chief_complaint=cc,
        red_flags=[RedFlag(flag=f, status="present") for f in flags],
        emergency=Emergency(
            answer=answer,  # type: ignore[arg-type]
            reasoning="Rule-based assessment (no AI model available): "
            + ("red flags detected." if flags else "no red flag detected; based on the user's answer."),
            triggering_red_flags=[safety_net.LABELS[f] for f in flags],
            acuity=2 if flags else (3 if answer == "UNCERTAIN" else 4),
        ),
        specialty=specialty,
        search_queries=_queries(specialty),
        appointment_timeframe=None if flags else "48 hours",
        patient_brief=f"{p.age or '?'}{p.sex or ''}, {cc[:80]}",
    )
    loc = session.location
    qa = "\n".join(f"  Q: {f['q']} A: {f['a']}" for f in session.followups) or "  none"
    note.note_text = "\n".join(
        [
            "CLINICAL INTAKE NOTE  (AI-generated. Not a diagnosis.)",
            f"Session: {session.id}            Time: {_now_iso()}",
            f"Patient: {p.age or 'unknown'}/{p.sex or 'unknown'}             Location: "
            f"{(loc.address if loc and loc.address else 'unknown')} "
            f"(landmark: {(loc.landmark if loc and loc.landmark else 'none')})",
            "",
            f"CC (Chief complaint): {cc}",
            "HPI (OLDCARTS): unknown (rule-based intake)",
            f"Follow-up answers:\n{qa}",
            "Red-flag screen: "
            + (
                "; ".join(f"{safety_net.LABELS[f]}: present" for f in flags)
                if flags
                else "no red flag detected"
            ),
            "",
            "Assessment (possible conditions, most likely first):",
            "  1. Needs clinical assessment - no AI model was available to suggest conditions",
            "",
            _emergency_block(note),
            "",
            "Plan: "
            + (
                "emergency dispatch"
                if answer != "NO"
                else f"find specialist appointment ({specialty}) within 48 hours"
            ),
        ]
    )
    return note


def _emergency_block(note: IntakeNote) -> str:
    e = note.emergency
    return "\n".join(
        [
            "EMERGENCY ASSESSMENT",
            "  Q: Could this be a medical emergency requiring an ambulance now?",
            f"  A: {e.answer}",
            f"  Reasoning: {e.reasoning or 'unknown'}",
            f"  Triggering red flags: {', '.join(e.triggering_red_flags) or 'none'}",
            f"  Acuity (1 = most critical … 5 = least): {e.acuity}",
        ]
    )
