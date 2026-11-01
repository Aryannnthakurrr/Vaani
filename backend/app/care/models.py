"""Data contracts for the symptom-to-care flow (docs/features/CARE.md; field names follow the workflow spec)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

EmergencyAnswer = Literal["YES", "NO", "UNCERTAIN"]
FlagStatus = Literal["present", "absent", "unknown"]
CallOutcome = Literal[
    "COMPLETED", "NO_ANSWER", "VOICEMAIL", "REFUSED", "WRONG_NUMBER", "TIMEOUT", "NOT_ALLOWED"
]
Route = Literal["A", "B"]


class Location(BaseModel):
    lat: float
    lng: float
    accuracy_m: float | None = None
    address: str | None = Field(default=None, max_length=300)
    landmark: str | None = Field(default=None, max_length=200)


class Patient(BaseModel):
    name: str | None = Field(default=None, max_length=60)
    age: int | None = Field(default=None, ge=0, le=120)
    sex: Literal["M", "F", "O"] | None = None
    phone: str | None = Field(default=None, max_length=20)
    conditions: list[str] = Field(default_factory=list, max_length=10)


class Hpi(BaseModel):
    """OLDCARTS: onset, location, duration, character, aggravating/relieving, radiation, timing, severity."""

    onset: str | None = None
    location: str | None = None
    duration: str | None = None
    character: str | None = None
    aggravating_relieving: str | None = None
    radiation: str | None = None
    timing: str | None = None
    severity: int | None = Field(default=None, ge=0, le=10)


class History(BaseModel):
    conditions: list[str] = Field(default_factory=list)
    medications: list[str] = Field(default_factory=list)
    allergies: list[str] = Field(default_factory=list)
    pregnancy: str | None = None


class RedFlag(BaseModel):
    flag: str
    status: FlagStatus


class Condition(BaseModel):
    name: str
    likelihood: Literal["high", "medium", "low"] = "medium"
    reason: str | None = None


class Emergency(BaseModel):
    answer: EmergencyAnswer
    reasoning: str = ""
    triggering_red_flags: list[str] = Field(default_factory=list)
    acuity: int = Field(default=3, ge=1, le=5)


class Preferences(BaseModel):
    """What the user said they want from the provider, beyond the medical need (used to filter and rank)."""

    max_fee_inr: int | None = None  # "not more than 500"
    insurance: str | None = None  # "Ayushman card", "CGHS", "Star Health"
    prefer: list[str] = Field(default_factory=list)  # hospitals the user asked for by name
    avoid: list[str] = Field(default_factory=list)  # hospitals the user does not want
    doctor_gender: Literal["female", "male"] | None = None


class IntakeNote(BaseModel):
    """The clinical intake note (§3.5): human-readable text plus the same facts as fields."""

    session_id: str = ""
    note_text: str = ""
    patient: Patient = Field(default_factory=Patient)
    chief_complaint: str = ""
    hpi: Hpi = Field(default_factory=Hpi)
    associated_symptoms: list[str] = Field(default_factory=list)
    pertinent_negatives: list[str] = Field(default_factory=list)
    history: History = Field(default_factory=History)
    red_flags: list[RedFlag] = Field(default_factory=list)
    possible_conditions: list[Condition] = Field(default_factory=list)
    emergency: Emergency
    specialty: str = "general medicine"
    required_facilities: list[str] = Field(default_factory=list)
    search_queries: list[str] = Field(default_factory=list)
    appointment_timeframe: str | None = None
    patient_brief: str = ""
    preferences: Preferences = Field(default_factory=Preferences)


class TriageOut(BaseModel):
    """What the triage model returns each turn: the next follow-up question, or the finished note."""

    next_question: str | None = None
    note: IntakeNote | None = None


class Hospital(BaseModel):
    id: str
    name: str
    address: str = ""
    lat: float | None = None
    lng: float | None = None
    phone: str | None = None  # shown to the user
    call_phone: str | None = None  # dialled (a teammate's number in DEMO_MODE)
    rating: float | None = None
    rating_count: int = 0
    types: list[str] = Field(default_factory=list)
    open_now: bool | None = None
    open_24h: bool = False
    eta_minutes: float | None = None
    capability: float = 0.5
    score: float = 0.0


class Slot(BaseModel):
    start: str
    end: str | None = None


class CallResult(BaseModel):
    """B4: one hospital's answers, extracted from the inquiry call."""

    hospital_id: str
    hospital_name: str
    call_sid: str | None = None
    call_outcome: CallOutcome = "COMPLETED"
    specialty_available: bool | None = None
    doctor_name: str | None = None
    slots: list[Slot] = Field(default_factory=list)
    fee_inr: int | None = None
    walk_in_ok: bool | None = None
    facilities_available: bool | None = (
        None  # every facility in the note's required_facilities, per the hospital
    )
    insurance: str | None = None
    insurance_accepted: bool | None = (
        None  # the user's own insurance (Preferences.insurance), when they had one
    )
    instructions: str | None = None
    eta_minutes: float | None = None
    address: str = ""
    confidence: Literal["high", "medium", "low"] = "low"


class Recommendation(BaseModel):
    primary: CallResult
    slot: Slot
    alternatives: list[CallResult] = Field(default_factory=list)
    spoken_text: str


class BookingResult(BaseModel):
    """B6: what the booking call achieved."""

    status: Literal["confirmed", "slot_unavailable", "failed"]
    doctor_name: str | None = None
    start_iso: str | None = None
    end_iso: str | None = None
    booking_ref: str | None = None
    instructions: str | None = None
    alternatives: list[Slot] = Field(default_factory=list)


# ---------------------------------------------------------------------------------------------- requests


class CareStart(BaseModel):
    location: Location | None = None
    patient: Patient = Field(default_factory=Patient)
    reply_style: Literal["en", "hi", "hinglish"] = "en"


class IntakeTurn(BaseModel):
    text: str = Field(min_length=1, max_length=2000)


class BookRequest(BaseModel):
    hospital_id: str
    slot: Slot
    patient: Patient | None = None
