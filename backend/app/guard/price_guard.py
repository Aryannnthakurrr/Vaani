"""The booking price check, server copy (twin of android/.../core/guard/PriceGuard.kt).

Today the phone runs it on its own OCR, so screenshots never leave the device. This copy keeps the rules ready for
the server (e.g. checking screens from a hosted agent): both must pass fixtures/price_guard/cases.json, so change
them together. Input is OCR lines with boxes; output is a verdict. No I/O.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal

TOLERANCE = 2  # rupees: surge rounding and ₹1 differences count as the same price

RideClass = Literal["CAR", "AUTO", "BIKE", "UNKNOWN"]

_PRICE = re.compile(r"(?:₹|Rs\.?|INR)\s?(\d{1,3}(?:,\d{3})*|\d+)(?:\.\d{1,2})?", re.IGNORECASE)
_CAR = re.compile(
    r"\b(go|sedan|premier|xl|mini|prime|cab|comfort|uberx|uber x|suv|hatchback|car)\b", re.IGNORECASE
)
_AUTO = re.compile(r"\b(auto|rickshaw|tuk)\b", re.IGNORECASE)
_BIKE = re.compile(r"\b(moto|bike|motorcycle|scooter)\b", re.IGNORECASE)


@dataclass(frozen=True)
class TextBox:
    text: str
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


@dataclass(frozen=True)
class RideOption:
    name: str
    ride_class: RideClass
    price: int
    top: int


@dataclass(frozen=True)
class Verdict:
    verdict: Literal["Ok", "CheaperSameType", "CheaperOtherType", "Unread"]
    quoted: int | None = None
    option: RideOption | None = None


def class_of(name: str) -> RideClass:
    if _AUTO.search(name):
        return "AUTO"
    if _BIKE.search(name):
        return "BIKE"
    if _CAR.search(name):
        return "CAR"
    return "UNKNOWN"


def price(text: str) -> int | None:
    m = _PRICE.search(text)
    return int(m.group(1).replace(",", "")) if m else None


def options(lines: list[TextBox]) -> list[RideOption]:
    """Ride rows: each price joined with the named option on the same row; a row's lowest price wins."""
    prices = [(b, p) for b in lines if (p := price(b.text)) is not None]
    names = [b for b in lines if price(b.text) is None and class_of(b.text) != "UNKNOWN"]
    found: dict[tuple[str, int], RideOption] = {}
    for box, amount in prices:
        half = max(box.bottom - box.top, 40)
        near = [n for n in names if abs(n.center_y - box.center_y) <= half]
        if not near:
            continue
        name = min(near, key=lambda n: abs(n.center_y - box.center_y))
        opt = RideOption(name.text.strip(), class_of(name.text), amount, name.top)
        key = (opt.name, opt.top)
        if key not in found or opt.price < found[key].price:
            found[key] = opt
    return sorted(found.values(), key=lambda o: o.top)


def check(lines: list[TextBox], wanted: RideClass, summary: str) -> Verdict:
    """[summary] is what the user agreed to; [wanted] is the ride type they asked for."""
    all_opts = options(lines)
    if not all_opts:
        return Verdict("Unread")
    named = next((o for o in all_opts if o.name.lower() in summary.lower()), None)
    quoted = price(summary)
    if quoted is None:
        quoted = named.price if named else None
    if quoted is None:
        return Verdict("Unread")
    ride_type: RideClass = wanted if wanted != "UNKNOWN" else (named.ride_class if named else "CAR")
    of_type = [o for o in all_opts if o.ride_class == ride_type]
    cheapest = min(of_type, key=lambda o: o.price) if of_type else None
    if cheapest and quoted > cheapest.price + TOLERANCE:
        return Verdict("CheaperSameType", quoted, cheapest)
    cars = [o for o in all_opts if o.ride_class == "CAR" and ride_type != "CAR"]
    other = min(cars, key=lambda o: o.price) if cars else None
    if other and other.price + TOLERANCE < quoted:
        return Verdict("CheaperOtherType", quoted, other)
    return Verdict("Ok", None, named or cheapest)
