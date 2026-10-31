"""Pydantic models shared by every route. The action schema mirrors docs/CONTRACTS.md."""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

ReplyStyle = Literal["en", "hi", "hinglish"]
Mode = Literal["ACT", "GUIDE"]


# --------------------------------------------------------------------------- agent actions
class _Action(BaseModel):
    model_config = ConfigDict(extra="ignore")
    reason: str = ""


class Tap(_Action):
    action: Literal["tap"]
    id: int


class TapXY(_Action):
    action: Literal["tap_xy"]
    x: int
    y: int


class TypeText(_Action):
    action: Literal["type"]
    id: int
    text: str


class Scroll(_Action):
    action: Literal["scroll"]
    direction: Literal["up", "down", "left", "right"]


class Back(_Action):
    action: Literal["back"]


class Home(_Action):
    action: Literal["home"]


class OpenApp(_Action):
    action: Literal["open_app"]
    package: str


class OpenDeeplink(_Action):
    action: Literal["open_deeplink"]
    uri: str


class Wait(_Action):
    action: Literal["wait"]
    ms: int = Field(ge=0, le=10_000)


class AskUser(_Action):
    action: Literal["ask_user"]
    question: str = Field(min_length=1)


ConfirmTarget = Annotated[Tap | TapXY, Field(discriminator="action")]


class Confirm(_Action):
    action: Literal["confirm"]
    summary: str = Field(min_length=1)
    then: ConfirmTarget


class Highlight(_Action):
    action: Literal["highlight"]
    id: int | None = None
    instruction: str = Field(min_length=1)
    gesture: Literal["swipe_up", "swipe_down"] | None = None


class Done(_Action):
    action: Literal["done"]
    message: str


class Fail(_Action):
    action: Literal["fail"]
    message: str


AgentAction = Annotated[
    Tap
    | TapXY
    | TypeText
    | Scroll
    | Back
    | Home
    | OpenApp
    | OpenDeeplink
    | Wait
    | AskUser
    | Confirm
    | Highlight
    | Done
    | Fail,
    Field(discriminator="action"),
]
ActionAdapter: TypeAdapter[Any] = TypeAdapter(AgentAction)
GUIDE_ACTIONS = {"highlight", "ask_user", "done", "fail"}
ACTION_NAMES = [
    "tap",
    "tap_xy",
    "type",
    "scroll",
    "back",
    "home",
    "open_app",
    "open_deeplink",
    "wait",
    "ask_user",
    "confirm",
    "highlight",
    "done",
    "fail",
]


class FlatThen(BaseModel):
    action: Literal["tap", "tap_xy"]
    id: int | None = None
    x: int | None = None
    y: int | None = None


class FlatAction(BaseModel):
    """Flat shape handed to Gemini as the response schema (unions are poorly supported there)."""

    action: Literal[
        "tap",
        "tap_xy",
        "type",
        "scroll",
        "back",
        "home",
        "open_app",
        "open_deeplink",
        "wait",
        "ask_user",
        "confirm",
        "highlight",
        "done",
        "fail",
    ]
    reason: str
    id: int | None = None
    x: int | None = None
    y: int | None = None
    text: str | None = None
    direction: Literal["up", "down", "left", "right"] | None = None
    package: str | None = None
    uri: str | None = None
    ms: int | None = None
    question: str | None = None
    summary: str | None = None
    then: FlatThen | None = None
    instruction: str | None = None
    gesture: Literal["swipe_up", "swipe_down"] | None = None
    message: str | None = None


def parse_action(data: dict[str, Any]) -> Any:
    """Validate a raw dict (possibly flat with nulls) into a typed action."""
    cleaned = {k: v for k, v in data.items() if v is not None}
    if isinstance(cleaned.get("then"), dict):
        cleaned["then"] = {k: v for k, v in cleaned["then"].items() if v is not None}
    return ActionAdapter.validate_python(cleaned)


def dump_action(action: Any) -> dict[str, Any]:
    return action.model_dump(exclude_none=True)


# --------------------------------------------------------------------------- agent step
class HistoryItem(BaseModel):
    model_config = ConfigDict(extra="ignore")
    action: dict[str, Any]
    reason: str = ""


class AgentStepRequest(BaseModel):
    goal: str
    mode: Mode = "ACT"
    language: ReplyStyle = "en"
    screen_text: str
    package: str = ""
    screenshot_b64: str | None = None
    history: list[HistoryItem] = Field(default_factory=list)
    task_hint: str | None = None
    run_id: str | None = Field(default=None, max_length=64)


class AgentOutcomeRequest(BaseModel):
    run_id: str = Field(max_length=64)
    outcome: Literal["done", "failed", "cancelled"]
    task_hint: str | None = None
    goal: str | None = None
    message: str | None = None


# --------------------------------------------------------------------------- intent
Intent = Literal[
    "book_cab",
    "book_house_help",
    "shop_online",
    "book_doctor",
    "care",
    "guide",
    "camera",
    "digest",
    "settings",
    "chitchat",
    "unknown",
]


class Slots(BaseModel):
    destination: str | None = None
    product: str | None = None
    specialty: str | None = None
    doctor: str | None = None
    area: str | None = None
    time: str | None = None
    date: str | None = None
    service: str | None = None
    quantity: int | None = None
    vehicle: str | None = None  # book_cab: "auto" or "bike" only when asked for; None means a car


class IntentRequest(BaseModel):
    utterance: str
    reply_style: ReplyStyle | None = None


class IntentResponse(BaseModel):
    intent: Intent
    slots: Slots = Field(default_factory=Slots)
    reply_style: ReplyStyle
    reply_text: str


# --------------------------------------------------------------------------- messages
Category = Literal[
    "bill_due",
    "subscription_renewal",
    "appointment",
    "delivery",
    "payment_done",
    "personal",
    "promo",
    "scam_suspected",
    "other",
]


class MessageIn(BaseModel):
    id: str
    sender: str = ""
    text: str


class ClassifyRequest(BaseModel):
    messages: list[MessageIn] = Field(max_length=100)


class ClassifiedMessage(BaseModel):
    id: str
    category: Category
    amount: float | None = None
    due_date: str | None = None
    merchant: str | None = None
    summary: str = ""


class ClassifyResponse(BaseModel):
    results: list[ClassifiedMessage]


class ExtractRequest(BaseModel):
    screen_text: str


class Appointment(BaseModel):
    doctor: str | None = None
    clinic: str | None = None
    address: str | None = None
    phone: str | None = None
    start_iso: str | None = None
    fee: float | None = None


class DigestItem(BaseModel):
    category: Category
    merchant: str | None = None
    amount: float | None = None
    due_date: str | None = None
    summary: str = ""


class DigestRequest(BaseModel):
    items: list[DigestItem]
    reply_style: ReplyStyle = "en"
    user_name: str | None = None
    today: str | None = None


class DigestResponse(BaseModel):
    text: str


# --------------------------------------------------------------------------- links
Verdict = Literal["safe", "suspicious", "dangerous"]


class LinkCheckRequest(BaseModel):
    url: str = Field(min_length=3, max_length=2048)


class LinkCheckResponse(BaseModel):
    url: str
    final_url: str
    verdict: Verdict
    reason_simple: str
    reason_simple_hi: str
    signals: list[str] = Field(default_factory=list)
    cached: bool = False


class LinkVerdict(BaseModel):
    verdict: Verdict
    reason_simple: str
    reason_simple_hi: str


# --------------------------------------------------------------------------- welfare / scam
class Heartbeat(BaseModel):
    device_id: str
    ts: int


class FamilyContact(BaseModel):
    name: str
    telegram_chat_id: str


class WelfareConfig(BaseModel):
    device_id: str
    user_name: str = "Saathi user"
    contacts: list[FamilyContact] = Field(default_factory=list)
    check_time: str = "12:00"


class DeviceOnly(BaseModel):
    device_id: str


class FamilyNotify(BaseModel):
    device_id: str
    summary: str = Field(max_length=500)


class SendResult(BaseModel):
    text: str = ""
    sent: list[str] = Field(default_factory=list)
    logged: bool = True


class ProductCard(BaseModel):
    name: str | None = None
    mrp: float | None = None
    quantity: str | None = None
    expiry_iso: str | None = None
    expiry_status: Literal["ok", "soon", "expired", "unknown"] = "unknown"
    confidence: float = 0.0
