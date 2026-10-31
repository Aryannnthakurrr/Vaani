"""Rule-based message classification, digest writing and appointment extraction (mock + fallback)."""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta

from app.schemas import Appointment, ClassifiedMessage, DigestItem, MessageIn

_CATEGORY_RULES: list[tuple[str, str]] = [
    (
        "scam_suspected",
        r"\bkyc\b|blocked|suspend|disconnect|lottery|\bprize\b|\bwon\b|customs|digital arrest|"
        r"\bcbi\b|police|\.apk\b|\*\d{2,3}\*|scan (the )?qr|refund.*upi|aadhaar.*(update|link)|"
        r"केवाईसी|ब्लॉक|लॉटरी|इनाम",
    ),
    ("appointment", r"appointment|\bdr\.\s|clinic|doctor|consultation|अपॉइंटमेंट"),
    (
        "payment_done",
        r"\bdebited\b|\bpaid\b|payment (of .+ )?(is )?(successful|received)|\bcredited\b|sent rs|"
        r"transaction successful|भुगतान सफल",
    ),
    ("subscription_renewal", r"renew|subscription|membership|auto-?debit|will be charged|auto pay"),
    ("bill_due", r"\bbill\b|\bdue\b|pay by|last date|बिल|देय"),
    ("delivery", r"deliver|shipped|dispatched|out for delivery|arriving|courier|parcel|डिलीवरी"),
    ("promo", r"\boffer\b|% ?off|\bsale\b|cashback|discount|t&c|\bdeal\b|coupon|ऑफर"),
]
_OTP = re.compile(
    r"\b\d{4,8}\b.{0,40}\b(otp|one time|verification code)|\b(otp|one time|code is)\b.{0,40}\b\d{4,8}\b",
    re.IGNORECASE,
)
_AMOUNT = re.compile(r"(?:₹|rs\.?|inr)\s?([\d,]+(?:\.\d{1,2})?)", re.IGNORECASE)
_MONTHS = {m: i for i, m in enumerate("jan feb mar apr may jun jul aug sep oct nov dec".split(), start=1)}
_DATE_NUM = re.compile(r"\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b")
_DATE_TXT = re.compile(
    r"\b(\d{1,2})[- ]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[- ,]*(\d{2,4})?\b",
    re.IGNORECASE,
)
_MERCHANTS = [
    "netflix",
    "airtel",
    "jio",
    "bses",
    "tata power",
    "amazon",
    "flipkart",
    "practo",
    "hotstar",
    "lic",
    "hdfc",
    "icici",
    "sbi",
    "swiggy",
    "zomato",
    "bescom",
    "vodafone",
    "vi ",
]


def parse_amount(text: str) -> float | None:
    m = _AMOUNT.search(text)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", ""))
    except ValueError:
        return None


def parse_date(text: str, today: date | None = None) -> str | None:
    today = today or date.today()
    m = _DATE_NUM.search(text)
    try:
        if m:
            day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
            year = year + 2000 if year < 100 else year
            return date(year, month, day).isoformat()
        m = _DATE_TXT.search(text)
        if m:
            day, month = int(m.group(1)), _MONTHS[m.group(2).lower()[:3]]
            year = int(m.group(3)) if m.group(3) else today.year
            year = year + 2000 if year < 100 else year
            return date(year, month, day).isoformat()
    except ValueError:
        return None
    low = text.lower()
    if "tomorrow" in low:
        return (today + timedelta(days=1)).isoformat()
    if "today" in low or "tonight" in low:
        return today.isoformat()
    return None


def merchant_from(sender: str, text: str) -> str | None:
    low = text.lower()
    for name in _MERCHANTS:
        if name in low:
            return name.strip().title() if len(name) > 3 else name.strip().upper()
    m = re.match(r"^[A-Z]{2}-([A-Z0-9]{3,9})$", sender.strip())
    return m.group(1) if m else None


def classify_one(msg: MessageIn, today: date | None = None) -> ClassifiedMessage:
    text = msg.text
    category = "other"
    if not _OTP.search(text):
        for name, pattern in _CATEGORY_RULES:
            if re.search(pattern, text, re.IGNORECASE):
                category = name
                break
    merchant = merchant_from(msg.sender, text)
    summary = re.sub(r"\s+", " ", text).strip()[:140]
    return ClassifiedMessage(
        id=msg.id,
        category=category,  # type: ignore[arg-type]
        amount=parse_amount(text) if category not in {"promo", "scam_suspected"} else None,
        due_date=parse_date(text, today)
        if category in {"bill_due", "subscription_renewal", "appointment", "delivery"}
        else None,
        merchant=merchant,
        summary=summary,
    )


# --------------------------------------------------------------------------- digest
_WEEKDAYS = {
    "en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
    "hi": ["सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार"],
    "hinglish": ["Somvaar", "Mangalvaar", "Budhvaar", "Guruvaar", "Shukravaar", "Shanivaar", "Ravivaar"],
}
_REL = {"en": ("today", "tomorrow", "on"), "hi": ("आज", "कल", ""), "hinglish": ("aaj", "kal", "")}


def when_phrase(due_iso: str | None, today: date, style: str) -> str:
    if not due_iso:
        return ""
    try:
        due = date.fromisoformat(due_iso)
    except ValueError:
        return ""
    delta = (due - today).days
    rel = _REL[style]
    if delta == 0:
        return rel[0]
    if delta == 1:
        return rel[1]
    if 1 < delta < 7:
        day = _WEEKDAYS[style][due.weekday()]
        return f"{rel[2]} {day}".strip()
    return due.strftime("%d %b")


def _money(amount: float | None) -> str:
    if amount is None:
        return ""
    return f"₹{amount:,.0f}" if amount == int(amount) else f"₹{amount:,.2f}"


def _line(item: DigestItem, today: date, style: str) -> str | None:
    who = item.merchant or {"en": "a bill", "hi": "एक बिल", "hinglish": "ek bill"}[style]
    amt, when = _money(item.amount), when_phrase(item.due_date, today, style)
    c = item.category
    if style == "en":
        lines = {
            "bill_due": f"Your {who} bill{' of ' + amt if amt else ''} is due{' ' + when if when else ''}.",
            "subscription_renewal": f"{who} renews{' ' + when if when else ''}{' for ' + amt if amt else ''}.",
            "appointment": f"You have an appointment{' ' + when if when else ''}: {item.summary[:80]}",
            "delivery": f"A delivery is coming{' ' + when if when else ''}.",
            "payment_done": f"{amt or 'Money'} was paid{' to ' + who if item.merchant else ''}.",
            "scam_suspected": "Careful: one message looks like a scam. Do not reply or click its link.",
        }
    elif style == "hi":
        lines = {
            "bill_due": f"आपका {who} का बिल{' ' + amt if amt else ''}{' ' + when if when else ''} भरना है।",
            "subscription_renewal": f"{who}{' ' + when if when else ''}{' ' + amt + ' में' if amt else ''} रिन्यू होगा।",
            "appointment": f"{when + ' ' if when else ''}आपका डॉक्टर का अपॉइंटमेंट है।",
            "delivery": f"{when + ' ' if when else ''}एक पार्सल आने वाला है।",
            "payment_done": f"{amt or 'पैसे'} का भुगतान हो गया।",
            "scam_suspected": "सावधान: एक संदेश धोखा लग रहा है। उसका जवाब मत दीजिए और लिंक मत खोलिए।",
        }
    else:
        lines = {
            "bill_due": f"Aapka {who} bill{' ' + amt if amt else ''}{' ' + when if when else ''} bharna hai.",
            "subscription_renewal": f"{who}{' ' + when if when else ''}{' ' + amt + ' mein' if amt else ''} renew hoga.",
            "appointment": f"{when.capitalize() + ' ' if when else ''}aapka doctor appointment hai.",
            "delivery": f"{when.capitalize() + ' ' if when else ''}ek parcel aane wala hai.",
            "payment_done": f"{amt or 'Paise'} ka payment ho gaya.",
            "scam_suspected": "Savdhaan: ek message dhokha lag raha hai. Uska jawab mat dijiye aur link mat kholiye.",
        }
    return lines.get(c)


def write_digest(items: list[DigestItem], style: str, user_name: str | None, today: date) -> str:
    greet = {"en": "Good morning", "hi": "सुप्रभात", "hinglish": "Good morning"}[style]
    name = f" {user_name} ji" if user_name else ""
    order = ["scam_suspected", "appointment", "bill_due", "subscription_renewal", "delivery", "payment_done"]
    picked = sorted((i for i in items if i.category in order), key=lambda i: order.index(i.category))
    lines = [line for i in picked if (line := _line(i, today, style))]
    if not lines:
        empty = {
            "en": "Nothing important today.",
            "hi": "आज कुछ ज़रूरी नहीं है।",
            "hinglish": "Aaj kuch zaroori nahi hai.",
        }[style]
        return f"{greet}{name}. {empty}"
    return f"{greet}{name}. " + " ".join(lines)


# --------------------------------------------------------------------------- appointment
def extract_appointment(screen_text: str, now: datetime | None = None) -> Appointment:
    now = now or datetime.now()
    text = screen_text
    doctor = re.search(r"(Dr\.?\s+[A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)", text)
    clinic = re.search(r"([A-Z][\w&' ]*(?:Clinic|Hospital|Centre|Center|Care))", text)
    address = re.search(r'"([^"\n]*(?:Sector|Road|Nagar|Marg|Vihar|Colony|Block)[^"\n]*)"', text)
    phone = re.search(r"(\+?91[\s-]?\d{5}[\s-]?\d{5}|\b\d{10}\b)", text)
    fee = re.search(r"Fee\s*₹\s?([\d,]+)", text, re.IGNORECASE)
    when = re.search(
        r"(Today|Tomorrow|\d{1,2}\s+\w{3})[^\d]{0,4}(\d{1,2}):(\d{2})\s*(AM|PM)", text, re.IGNORECASE
    )
    start_iso = None
    if when:
        day = now.date()
        if when.group(1).lower() == "tomorrow":
            day += timedelta(days=1)
        hour = int(when.group(2)) % 12 + (12 if when.group(4).upper() == "PM" else 0)
        start_iso = datetime(day.year, day.month, day.day, hour, int(when.group(3))).isoformat()
    return Appointment(
        doctor=doctor.group(1) if doctor else None,
        clinic=clinic.group(1).strip() if clinic else None,
        address=address.group(1).strip() if address else None,
        phone=re.sub(r"[\s-]", "", phone.group(1)) if phone else None,
        start_iso=start_iso,
        fee=float(fee.group(1).replace(",", "")) if fee else None,
    )
