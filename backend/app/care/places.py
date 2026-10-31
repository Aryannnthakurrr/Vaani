"""Hospital search (Places API, New) and travel times (Routes API) for Path B (workflow §5 B1–B2).

Needs GOOGLE_MAPS_API_KEY. Without it, DEMO_MODE returns a few clearly labelled demo hospitals around the user so
the calling flow can be rehearsed; outside demo mode the search reports that the key is missing.
"""

from __future__ import annotations

import logging
import math
import os
import re

import httpx

from app.care.models import Hospital, Location

log = logging.getLogger("saathi.care.places")

PLACES = "https://places.googleapis.com/v1/places"
ROUTE_MATRIX = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix"
FIELD_MASK = (
    "places.id,places.displayName,places.formattedAddress,places.location,places.nationalPhoneNumber,"
    "places.internationalPhoneNumber,places.currentOpeningHours,places.rating,places.userRatingCount,places.types"
)
RADII_M = (8000, 15000, 25000)
FALLBACK_KMPH = 22.0  # city driving speed for ETAs when the Routes API is unavailable


class PlacesUnavailable(RuntimeError):
    pass


def maps_key() -> str:
    return os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()


def demo_mode() -> bool:
    return os.environ.get("DEMO_MODE", "").strip().lower() in {"1", "true", "yes", "on"}


def _circle(loc: Location, radius_m: int) -> dict[str, object]:
    return {"circle": {"center": {"latitude": loc.lat, "longitude": loc.lng}, "radius": float(radius_m)}}


def _hospital(place: dict) -> Hospital:
    hours = place.get("currentOpeningHours") or {}
    periods = hours.get("periods") or []
    open_24h = len(periods) == 1 and "close" not in periods[0]
    loc = place.get("location") or {}
    return Hospital(
        id=str(place.get("id", "")),
        name=str((place.get("displayName") or {}).get("text", "Hospital")),
        address=str(place.get("formattedAddress", "")),
        lat=loc.get("latitude"),
        lng=loc.get("longitude"),
        phone=place.get("internationalPhoneNumber") or place.get("nationalPhoneNumber"),
        rating=place.get("rating"),
        rating_count=int(place.get("userRatingCount") or 0),
        types=list(place.get("types") or []),
        open_now=hours.get("openNow"),
        open_24h=open_24h,
    )


async def _text_search(
    client: httpx.AsyncClient, key: str, query: str, loc: Location, radius: int
) -> list[dict]:
    resp = await client.post(
        f"{PLACES}:searchText",
        headers={"X-Goog-Api-Key": key, "X-Goog-FieldMask": FIELD_MASK},
        json={"textQuery": query, "locationBias": _circle(loc, radius), "maxResultCount": 20},
    )
    resp.raise_for_status()
    return list(resp.json().get("places") or [])


async def _nearby(client: httpx.AsyncClient, key: str, loc: Location, radius: int) -> list[dict]:
    resp = await client.post(
        f"{PLACES}:searchNearby",
        headers={"X-Goog-Api-Key": key, "X-Goog-FieldMask": FIELD_MASK},
        json={
            "includedTypes": ["hospital"],
            "rankPreference": "DISTANCE",
            "maxResultCount": 20,
            "locationRestriction": _circle(loc, radius),
        },
    )
    resp.raise_for_status()
    return list(resp.json().get("places") or [])


async def search(
    loc: Location, queries: list[str], radius_m: int, specialty: str = "general medicine"
) -> list[Hospital]:
    """Text Search for each query plus Nearby Search for hospitals, merged and de-duplicated by place id."""
    key = maps_key()
    if not key:
        if demo_mode():
            return demo_hospitals(loc, specialty)
        raise PlacesUnavailable("Hospital search needs GOOGLE_MAPS_API_KEY.")
    seen: dict[str, Hospital] = {}
    async with httpx.AsyncClient(timeout=15) as client:
        batches = [await _nearby(client, key, loc, radius_m)]
        for query in queries[:3]:
            batches.append(await _text_search(client, key, query, loc, radius_m))
    for batch in batches:
        for place in batch:
            h = _hospital(place)
            if h.id and h.id not in seen:
                seen[h.id] = h
    return list(seen.values())


def _seconds(duration: str | None) -> float | None:
    m = re.fullmatch(r"([\d.]+)s", duration or "")
    return float(m.group(1)) if m else None


def haversine_km(a_lat: float, a_lng: float, b_lat: float, b_lng: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lng - a_lng)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


async def travel_minutes(origin: Location, hospitals: list[Hospital]) -> None:
    """Fills `eta_minutes` with one traffic-aware route matrix call; straight-line estimate when unavailable."""
    for h in hospitals:
        if h.lat is not None and h.lng is not None:
            h.eta_minutes = round(
                haversine_km(origin.lat, origin.lng, h.lat, h.lng) * 1.3 / FALLBACK_KMPH * 60, 1
            )
    key = maps_key()
    targets = [h for h in hospitals if h.lat is not None and h.lng is not None]
    if not key or not targets:
        return
    body = {
        "origins": [
            {"waypoint": {"location": {"latLng": {"latitude": origin.lat, "longitude": origin.lng}}}}
        ],
        "destinations": [
            {"waypoint": {"location": {"latLng": {"latitude": h.lat, "longitude": h.lng}}}} for h in targets
        ],
        "travelMode": "DRIVE",
        "routingPreference": "TRAFFIC_AWARE",
    }
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                ROUTE_MATRIX,
                headers={
                    "X-Goog-Api-Key": key,
                    "X-Goog-FieldMask": "originIndex,destinationIndex,duration,distanceMeters,condition",
                },
                json=body,
            )
            resp.raise_for_status()
            for cell in resp.json():
                seconds = _seconds(cell.get("duration"))
                if cell.get("condition") == "ROUTE_EXISTS" and seconds is not None:
                    targets[int(cell.get("destinationIndex", 0))].eta_minutes = round(seconds / 60, 1)
    except (httpx.HTTPError, ValueError, IndexError) as exc:
        log.warning("route matrix failed, keeping straight-line ETAs: %s", type(exc).__name__)


# Demo channel (no Maps key): three places that fit the specialty. Generic names, labelled "(demo)", so nobody
# mistakes them for real hospitals; every call goes to the team's own number (ranking.assign_call_numbers).
_DEMO_BY_SPECIALTY = {
    "cardiology": [
        "City Heart & Cardiac Centre",
        "Lifeline Heart Institute",
        "Metro Multispeciality Hospital",
    ],
    "neurology": ["NeuroCare Brain & Spine Centre", "City Neuro Hospital", "Metro Multispeciality Hospital"],
    "pulmonology": ["BreatheWell Chest Clinic", "City Chest & TB Hospital", "Metro Multispeciality Hospital"],
    "gastroenterology": [
        "DigestiCare Gastro Centre",
        "City Liver & Gut Hospital",
        "Metro Multispeciality Hospital",
    ],
    "orthopedics": ["Bone & Joint Care Hospital", "City Ortho Centre", "Metro Multispeciality Hospital"],
    "dermatology": [
        "SkinCare Dermatology Clinic",
        "City Skin & Hair Centre",
        "Metro Multispeciality Hospital",
    ],
    "ent": ["City ENT Hospital", "Ear Nose Throat Care Centre", "Metro Multispeciality Hospital"],
    "ophthalmology": ["City Eye Hospital", "Clear Vision Eye Centre", "Metro Multispeciality Hospital"],
    "gynecology": ["Mother & Child Care Hospital", "City Women's Hospital", "Metro Multispeciality Hospital"],
}
_DEMO_DEFAULT = ["Metro Multispeciality Hospital", "City General Hospital", "Sunrise Family Clinic"]


def demo_hospitals(loc: Location, specialty: str = "general medicine") -> list[Hospital]:
    """Three stand-in hospitals that make sense for `specialty`, a short drive from the user."""
    names = _DEMO_BY_SPECIALTY.get(specialty.lower(), _DEMO_DEFAULT)
    spots = [(0.012, 0.008, 4.5, 2100), (-0.018, 0.015, 4.3, 900), (0.025, -0.02, 4.2, 3100)]
    return [
        Hospital(
            id=f"demo/{i}",
            name=f"{name} (demo)",
            address=f"Demo address {i}, near {loc.address or 'you'}",
            lat=loc.lat + dlat,
            lng=loc.lng + dlng,
            phone=f"+91 99999 0000{i}",
            rating=rating,
            rating_count=count,
            types=["hospital"],
            open_now=True,
        )
        for i, (name, (dlat, dlng, rating, count)) in enumerate(zip(names, spots, strict=True), start=1)
    ]
