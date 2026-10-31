"""Path B end to end (workflow §5): find → rank → parallel inquiry calls → recommend → booking call.

Runs as background tasks; progress reaches the app as session events (docs/CARE.md). The calendar entry is written
on the phone (the app already owns calendar access), from the `booking_status: confirmed` event.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime

from app.care import calls, extract, places, ranking
from app.care.calls import CallsUnavailable
from app.care.models import BookingResult, CallResult, Hospital, Location, Patient, Recommendation, Slot
from app.care.session import CareSession
from app.llm.base import LLMClient

log = logging.getLogger("saathi.care")
_RETRY_OUTCOMES = {"NO_ANSWER", "VOICEMAIL", "TIMEOUT", "NOT_ALLOWED", "WRONG_NUMBER"}
_tasks: set[asyncio.Task] = set()
DEMO_LOCATION = Location(lat=28.4595, lng=77.0266, address="Gurugram (demo location)")


def spawn(coro) -> None:  # type: ignore[no-untyped-def]
    """Runs `coro` in the background and keeps a reference so it isn't garbage-collected mid-call."""
    task = asyncio.create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


def _tr(style: str, en: str, hi: str, hinglish: str) -> str:
    return {"hi": hi, "hinglish": hinglish}.get(style, en)


def _public(h: Hospital) -> dict[str, object]:
    return h.model_dump(exclude={"call_phone", "types"})


# ------------------------------------------------------------------------------------------- B1–B5


async def find_and_call(session: CareSession, llm: LLMClient) -> None:
    try:
        await _find_and_call(session, llm)
    except Exception as exc:  # the app must always hear back, whatever broke
        log.exception("care path B failed session=%s", session.id)
        session.emit("error", stage="care", message=str(exc)[:200])


async def _find_and_call(session: CareSession, llm: LLMClient) -> None:
    note, loc = session.note, session.location
    if loc is None and places.demo_mode():
        loc = session.location = (
            DEMO_LOCATION  # the demo channel skips location; hospitals are stand-ins anyway
        )
    if note is None or loc is None:
        session.emit("error", stage="B1", message="Location and intake are needed first.")
        return
    session.emit("care_status", stage="searching")
    found: list[Hospital] = []
    try:
        for radius in places.RADII_M:
            found = ranking.filter_candidates(
                await places.search(loc, note.search_queries, radius, note.specialty), note.specialty
            )
            if len(found) >= 5:
                break
    except places.PlacesUnavailable as exc:
        session.emit("error", stage="B1", message=str(exc))
        return
    await places.travel_minutes(loc, found)
    await ranking.score_capability(llm, found, note.specialty)
    ranked = ranking.rank(found)
    ranking.assign_call_numbers(ranked, ranking.mask_numbers())
    session.hospitals = ranked
    to_call, backups = ranking.split_calls(ranked)
    session.backups = [h.id for h in backups]
    session.emit(
        "hospitals_found", hospitals=[_public(h) for h in ranked[:8]], calling=[h.id for h in to_call]
    )
    if not to_call:
        session.emit("error", stage="B1", message="No open hospital with a phone number was found nearby.")
        return
    if not calls.calls_ready():
        session.emit("error", stage="B3", message="Hospital calls are not set up (Bolna agents missing).")
        session.emit("care_fallback", hospitals=[_public(h) for h in ranked[:5]])
        return
    backup_queue: asyncio.Queue[Hospital] = asyncio.Queue()
    if len({h.call_phone for h in to_call}) < len(to_call):
        # One phone plays several hospitals (the demo channel): it can only take one call at a time, and backups
        # would ring the same phone again, so call one after another with no backups.
        results = []
        for h in to_call:
            if session.live_booked:
                break  # already booked during an earlier call
            results.append(await _inquire_with_backup(session, llm, h, backup_queue))
    else:
        for h in backups:
            backup_queue.put_nowait(h)
        results = await asyncio.gather(
            *(_inquire_with_backup(session, llm, h, backup_queue) for h in to_call)
        )
    _recommend(session, [r for r in results if r is not None])


async def _inquire_with_backup(
    session: CareSession, llm: LLMClient, first: Hospital, backups: asyncio.Queue[Hospital]
) -> CallResult | None:
    hospital: Hospital | None = first
    while hospital is not None:
        result = await _inquire(session, llm, hospital)
        session.results[hospital.id] = result
        session.emit("call_result", **result.model_dump())
        if result.call_outcome not in _RETRY_OUTCOMES:
            return result
        hospital = backups.get_nowait() if not backups.empty() else None
    return None


async def _inquire(session: CareSession, llm: LLMClient, hospital: Hospital) -> CallResult:
    note = session.note
    assert note is not None
    user_data = {
        "hospital_name": hospital.name,
        "specialty": note.specialty,
        "timeframe": note.appointment_timeframe or "the next 48 hours",
        "patient_brief": note.patient_brief or note.chief_complaint,
        "needs": ", ".join([f"{note.specialty} consultation", *note.required_facilities]),
        "language": session.style,
        # References the agent passes back to check_with_patient, so the answer reaches the right phone and hospital.
        "session_ref": session.id,
        "hospital_ref": hospital.id,
    }
    try:
        run = await calls.run_call(session, hospital.id, hospital.call_phone, "inquiry", user_data)
        if hospital.id in session.live_booked and await _booked_on_the_call(
            session, llm, hospital, run.transcript
        ):
            return CallResult(hospital_id=hospital.id, hospital_name=hospital.name, call_sid=run.execution_id,
                              call_outcome="COMPLETED", eta_minutes=hospital.eta_minutes, address=hospital.address)  # fmt: skip
    except CallsUnavailable as exc:
        session.emit("error", stage="B3", message=str(exc))
        return CallResult(hospital_id=hospital.id, hospital_name=hospital.name, call_outcome="NO_ANSWER")
    if run.outcome != "COMPLETED":
        return CallResult(
            hospital_id=hospital.id, hospital_name=hospital.name, call_sid=run.execution_id,
            call_outcome=run.outcome, eta_minutes=hospital.eta_minutes, address=hospital.address,
        )  # fmt: skip
    return await extract.inquiry_result(llm, hospital, note.specialty, run.transcript, run.execution_id)


def _recommend(session: CareSession, results: list[CallResult]) -> None:
    if session.live_booked:
        return  # the user said yes while a hospital was on the line; that call booked it
    by_id = {h.id: h for h in session.hospitals}
    ordered = ranking.rerank(results, by_id)
    if not ordered:
        session.emit("no_options", message=_tr(session.style,
            "No hospital could confirm an appointment. Here are the nearest ones so you can call yourself.",
            "किसी अस्पताल ने अपॉइंटमेंट पक्का नहीं किया। पास के अस्पताल ये हैं, आप खुद फ़ोन कर सकते हैं।",
            "Kisi hospital ne appointment confirm nahi kiya. Paas ke hospitals yeh hain, aap khud call kar sakte hain."),
            hospitals=[_public(h) for h in session.hospitals[:5]])  # fmt: skip
        return
    best = ordered[0]
    slot = ranking.best_slot(best)
    assert slot is not None
    rec = Recommendation(
        primary=best, slot=slot, alternatives=ordered[1:], spoken_text=spoken(session, best, slot)
    )
    session.recommendation = rec
    session.emit("recommendation", **rec.model_dump())


def human_time(iso: str, style: str) -> str:
    try:
        when = datetime.fromisoformat(iso)
    except ValueError:
        return iso
    today = datetime.now(when.tzinfo).date()
    days = (when.date() - today).days
    day = {0: _tr(style, "today", "आज", "aaj"), 1: _tr(style, "tomorrow", "कल", "kal")}.get(
        days, when.strftime("%A %d %B")
    )
    return f"{day} {when.strftime('%I:%M %p').lstrip('0')}"


def spoken(session: CareSession, r: CallResult, slot: Slot) -> str:
    doctor = r.doctor_name or _tr(session.style, "a doctor", "डॉक्टर", "doctor")
    specialty = session.note.specialty.title() if session.note else ""
    when = human_time(slot.start, session.style)
    fee = f", ₹{r.fee_inr}" if r.fee_inr else ""
    eta = f", {round(r.eta_minutes)} min" if r.eta_minutes else ""
    return _tr(
        session.style,
        f"Best option: {doctor} ({specialty}) at {r.hospital_name}, {when}{fee}{eta} away. "
        "Should I book this appointment?",
        f"सबसे अच्छा विकल्प: {r.hospital_name} में {doctor} ({specialty}), {when}{fee}{eta}। "
        "क्या मैं यह अपॉइंटमेंट बुक करूँ?",
        f"Best option: {r.hospital_name} mein {doctor} ({specialty}), {when}{fee}{eta}. "
        "Kya main yeh appointment book karun?",
    )


# ---------------------------------------------------------------------------------------------- B6


async def book(session: CareSession, llm: LLMClient, hospital_id: str, slot: Slot, patient: Patient) -> None:
    try:
        await _book(session, llm, hospital_id, slot, patient)
    except Exception as exc:
        log.exception("care booking failed session=%s", session.id)
        session.emit("error", stage="B6", message=str(exc)[:200])


async def _book(session: CareSession, llm: LLMClient, hospital_id: str, slot: Slot, patient: Patient) -> None:
    hospital = session.hospital(hospital_id)
    result = session.results.get(hospital_id)
    if hospital is None:
        session.emit("error", stage="B6", message="Unknown hospital.")
        return
    session.chosen = (hospital_id, slot)
    session.booking_attempts += 1
    session.emit("booking_status", status="calling", hospital_id=hospital_id, slot=slot.model_dump())
    user_data = {
        "hospital_name": hospital.name,
        "specialty": session.note.specialty if session.note else "",
        "doctor": (result.doctor_name if result and result.doctor_name else "the doctor"),
        "slot_time": human_time(slot.start, "en"),
        "patient_name": patient.name or "the patient",
        "patient_age": str(patient.age or ""),
        "patient_phone": patient.phone or "",
    }
    try:
        run = await calls.run_call(session, hospital_id, hospital.call_phone, "booking", user_data)
        booking = (
            await extract.booking_result(llm, run.transcript, slot)
            if run.outcome == "COMPLETED"
            else BookingResult(status="failed")
        )
    except CallsUnavailable as exc:
        session.emit("error", stage="B6", message=str(exc))
        booking = BookingResult(status="failed")
    session.booking = booking
    details = booking.model_dump()
    if booking.status == "confirmed":
        details["calendar"] = _calendar_event(session, hospital, booking, slot)
    elif booking.status == "failed" and session.booking_attempts >= 2:
        details["call_yourself"] = hospital.phone  # §10: two failed booking calls → the user calls
    session.emit("booking_status", hospital_id=hospital_id, **details)


async def _booked_on_the_call(
    session: CareSession, llm: LLMClient, hospital: Hospital, transcript: list[dict[str, str]]
) -> bool:
    """The user said yes mid-call: read the booking from that call's transcript and finish like a booking call.

    Only a booking the transcript confirms counts. Otherwise the yes is dropped and the usual recommendation follows,
    so the user is never told "booked" on a guess.
    """
    slot = session.live_booked[hospital.id]
    booking = await extract.booking_result(llm, transcript, slot)
    if booking.status != "confirmed":
        session.live_booked.pop(hospital.id, None)
        session.emit("live_confirm_unclear", hospital_id=hospital.id)
        return False
    session.chosen = (hospital.id, slot)
    session.booking = booking
    details = booking.model_dump()
    details["calendar"] = _calendar_event(session, hospital, booking, slot)
    session.emit("booking_status", hospital_id=hospital.id, during_inquiry=True, **details)
    return True


def _calendar_event(
    session: CareSession, hospital: Hospital, b: BookingResult, slot: Slot
) -> dict[str, object]:
    """Fields for the phone's calendar insert (B7); end defaults to start + 30 min (done on the phone)."""
    note = session.note
    inquiry = session.results.get(hospital.id)
    specialty = note.specialty.title() if note else ""
    doctor = b.doctor_name or (inquiry.doctor_name if inquiry else None) or "Doctor"
    lines = [
        f"Booking ref: {b.booking_ref or 'not given'}",
        f"Phone: {hospital.phone or ''}",
        f"Fee: ₹{inquiry.fee_inr}" if inquiry and inquiry.fee_inr else "",
        f"Instructions: {b.instructions}" if b.instructions else "",
        f"Reason for visit: {note.chief_complaint}" if note else "",
        "",
        "Booked by the Saathi assistant.",
    ]
    return {
        "title": f"{doctor} ({specialty}), {hospital.name}",
        "location": hospital.address,
        "description": "\n".join(line for line in lines if line),
        "start_iso": b.start_iso or slot.start,
        "end_iso": b.end_iso or slot.end,
        "reminders_min": [1440, 120],
        "navigate_url": f"https://www.google.com/maps/dir/?api=1&destination={hospital.lat},{hospital.lng}"
        if hospital.lat is not None
        else None,
    }
