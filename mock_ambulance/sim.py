"""Dispatch records and the ambulance simulation (workflow §4 A4)."""

from __future__ import annotations

import asyncio
import itertools
import logging
import os
import random
import secrets
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from mock_ambulance import polyline
from mock_ambulance.polyline import Point

log = logging.getLogger("mock_ambulance")
IST = ZoneInfo("Asia/Kolkata")
ORDER = [
    "RECEIVED", "ASSIGNED", "EN_ROUTE", "ARRIVED_AT_SCENE", "PATIENT_ON_BOARD",
    "EN_ROUTE_TO_HOSPITAL", "ARRIVED_AT_HOSPITAL", "COMPLETED",
]  # fmt: skip
CREW = [
    ("Suresh K", "Anita R"),
    ("Ramesh P", "Fatima S"),
    ("Gurpreet S", "Meena D"),
    ("Arjun N", "Lakshmi V"),
]
EQUIPMENT = {
    "ALS": ["defibrillator", "oxygen", "ventilator", "cardiac monitor"],
    "BLS": ["oxygen", "stretcher", "first aid"],
}


def now_iso() -> str:
    return datetime.now(IST).isoformat(timespec="seconds")


def speed_multiplier() -> float:
    try:
        return max(0.1, float(os.environ.get("SIM_SPEED_MULTIPLIER", "1")))
    except ValueError:
        return 1.0


@dataclass
class Dispatch:
    id: str
    request: dict[str, Any]
    patient: Point
    status: str = "RECEIVED"
    created_at: str = field(default_factory=now_iso)
    timeline: list[dict[str, str]] = field(default_factory=list)
    vehicle: dict[str, Any] | None = None
    crew: dict[str, Any] | None = None
    route: list[Point] = field(default_factory=list)
    position: Point | None = None
    heading: float = 0.0
    speed_kmph: float = 0.0
    eta_sec: int | None = None
    distance_m: int | None = None
    updated_at: str | None = None
    destination_hospital: dict[str, Any] | None = None
    subscribers: list[asyncio.Queue[dict[str, Any]]] = field(default_factory=list)
    task: asyncio.Task | None = None

    def snapshot(self) -> dict[str, Any]:
        loc = None
        if self.position:
            loc = {"lat": self.position[0], "lng": self.position[1], "heading": round(self.heading),
                   "speed_kmph": round(self.speed_kmph), "updated_at": self.updated_at}  # fmt: skip
        return {
            "dispatch_id": self.id,
            "status": self.status,
            "priority": self.request.get("priority"),
            "vehicle": self.vehicle,
            "crew": self.crew,
            "location": loc,
            "eta_sec": self.eta_sec,
            "distance_m": self.distance_m,
            "route_polyline": polyline.encode(self.route) if self.route else None,
            "destination_hospital": self.destination_hospital,
            "timeline": self.timeline,
        }

    def publish(self, event: dict[str, Any]) -> None:
        for q in list(self.subscribers):
            q.put_nowait(event)
        hook = (self.request.get("callback") or {}).get("webhook_url")
        if hook:
            asyncio.get_running_loop().create_task(_webhook(hook, event))

    def set_status(self, status: str) -> None:
        self.status = status
        self.timeline.append({"status": status, "at": now_iso()})
        self.publish({"event": "status", "dispatch_id": self.id, "status": status, "vehicle": self.vehicle,
                      "crew": self.crew, "at": now_iso()})  # fmt: skip


async def _webhook(url: str, event: dict[str, Any]) -> None:
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            await client.post(
                url,
                json=event,
                headers={"X-Api-Key": os.environ.get("MOCK_AMBULANCE_API_KEY", "dev-key")},
            )
    except httpx.HTTPError:
        pass  # webhooks are best-effort; tracking also works by polling or WebSocket


class Store:
    def __init__(self) -> None:
        self.items: dict[str, Dispatch] = {}

    def create(self, request: dict[str, Any]) -> Dispatch:
        loc = request["location"]
        d = Dispatch(
            id="dsp_" + secrets.token_hex(3),
            request=request,
            patient=(float(loc["lat"]), float(loc["lng"])),
        )
        d.timeline.append({"status": "RECEIVED", "at": d.created_at})
        self.items[d.id] = d
        return d


STORE = Store()


async def route_to(origin: Point, dest: Point) -> list[Point]:
    """A driving route from Google Routes computeRoutes, or a synthetic street-grid path without a key."""
    key = os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()
    if key:
        body = {
            "origin": {"location": {"latLng": {"latitude": origin[0], "longitude": origin[1]}}},
            "destination": {"location": {"latLng": {"latitude": dest[0], "longitude": dest[1]}}},
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE",
        }
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                resp = await client.post(
                    "https://routes.googleapis.com/directions/v2:computeRoutes",
                    headers={
                        "X-Goog-Api-Key": key,
                        "X-Goog-FieldMask": "routes.polyline.encodedPolyline",
                    },
                    json=body,
                )
                resp.raise_for_status()
                points = polyline.decode(resp.json()["routes"][0]["polyline"]["encodedPolyline"])
                if len(points) >= 2:
                    return points
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as exc:
            log.warning("computeRoutes failed, using a synthetic route: %s", type(exc).__name__)
    return _grid_route(origin, dest)


def _grid_route(a: Point, b: Point) -> list[Point]:
    """Zig-zags along north-south and east-west legs, so the marker moves like a car on a street grid."""
    legs = 4
    points = [a]
    for i in range(1, legs + 1):
        lat = a[0] + (b[0] - a[0]) * i / legs
        prev = points[-1]
        points.append((lat, prev[1]))
        points.append((lat, a[1] + (b[1] - a[1]) * i / legs))
    dense: list[Point] = [points[0]]
    for p, q in itertools.pairwise(points):
        steps = max(1, int(polyline.distance_m(p, q) // 60))
        dense += [
            (p[0] + (q[0] - p[0]) * k / steps, p[1] + (q[1] - p[1]) * k / steps) for k in range(1, steps + 1)
        ]
    return dense


async def simulate(d: Dispatch, assignment_sec: int) -> None:
    """RECEIVED → ASSIGNED → EN_ROUTE → ARRIVED_AT_SCENE → PATIENT_ON_BOARD → EN_ROUTE_TO_HOSPITAL →
    ARRIVED_AT_HOSPITAL → COMPLETED, with a location event every second while driving."""
    mult = speed_multiplier()
    service = str(d.request.get("service_level", "BLS"))
    base = polyline.offset(d.patient, random.uniform(2000, 5000), random.uniform(0, 360))
    d.route = await route_to(base, d.patient)
    await asyncio.sleep(max(1.0, assignment_sec / mult))
    if d.status == "CANCELLED":
        return
    driver, paramedic = random.choice(CREW)
    number = random.randint(1000, 9999)
    d.vehicle = {"vehicle_id": f"AMB-{number:04d}", "registration": f"KA01AB{number:04d}", "type": service,
                 "equipment": EQUIPMENT.get(service, EQUIPMENT["BLS"])}  # fmt: skip
    d.crew = {
        "driver": {"name": driver, "phone_masked": f"+91XXXXXX{number:04d}"},
        "paramedic": {"name": paramedic},
    }
    d.position, d.heading, _ = polyline.along(d.route, 0)
    d.set_status("ASSIGNED")
    await asyncio.sleep(max(1.0, 3 / mult))
    d.set_status("EN_ROUTE")
    if not await _drive(d, "EN_ROUTE", mult):
        return
    d.set_status("ARRIVED_AT_SCENE")
    # The optional hospital leg (A4 step 5): pick-up, drive to the nearest capable hospital, hand over.
    await asyncio.sleep(max(1.0, 60 / mult))
    if d.status != "ARRIVED_AT_SCENE":
        return  # cancelled at the scene
    d.set_status("PATIENT_ON_BOARD")
    hospital = polyline.offset(d.patient, random.uniform(3000, 6000), random.uniform(0, 360))
    d.destination_hospital = {
        "name": "Nearest capable hospital (demo)",
        "lat": hospital[0],
        "lng": hospital[1],
    }
    d.route = await route_to(d.patient, hospital)
    await asyncio.sleep(max(1.0, 10 / mult))
    d.set_status("EN_ROUTE_TO_HOSPITAL")
    if not await _drive(d, "EN_ROUTE_TO_HOSPITAL", mult):
        return
    d.set_status("ARRIVED_AT_HOSPITAL")
    await asyncio.sleep(max(1.0, 30 / mult))
    d.set_status("COMPLETED")


async def _drive(d: Dispatch, status: str, mult: float) -> bool:
    """Moves along `d.route`, a location event every second; False if the status changed (e.g. cancelled)."""
    travelled = 0.0
    total = polyline.total_m(d.route)
    while d.status == status and travelled < total:
        await asyncio.sleep(1.0)
        d.speed_kmph = random.uniform(25, 45)
        travelled = min(total, travelled + d.speed_kmph / 3.6 * mult)
        d.position, d.heading, left = polyline.along(d.route, travelled)
        d.distance_m = round(left)
        d.eta_sec = round(left / (35 / 3.6) / mult)  # at the average speed, in simulated seconds
        d.updated_at = now_iso()
        d.publish({"event": "location", "dispatch_id": d.id, "lat": d.position[0], "lng": d.position[1],
                   "heading": round(d.heading), "speed_kmph": round(d.speed_kmph), "eta_sec": d.eta_sec,
                   "distance_m": d.distance_m, "at": d.updated_at})  # fmt: skip
    if d.status != status:
        return False
    d.speed_kmph, d.eta_sec, d.distance_m = 0.0, 0, 0
    return True
