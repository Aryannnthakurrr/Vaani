"""Google encoded-polyline format (precision 5) and small geometry helpers."""

from __future__ import annotations

import itertools
import math

Point = tuple[float, float]  # (lat, lng)


def decode(encoded: str) -> list[Point]:
    points: list[Point] = []
    index = lat = lng = 0
    while index < len(encoded):
        for axis in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if axis == 0:
                lat += delta
            else:
                lng += delta
        points.append((lat / 1e5, lng / 1e5))
    return points


def encode(points: list[Point]) -> str:
    out: list[str] = []
    prev_lat = prev_lng = 0
    for lat, lng in points:
        ilat, ilng = round(lat * 1e5), round(lng * 1e5)
        for delta in (ilat - prev_lat, ilng - prev_lng):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                out.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            out.append(chr(value + 63))
        prev_lat, prev_lng = ilat, ilng
    return "".join(out)


def distance_m(a: Point, b: Point) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def bearing(a: Point, b: Point) -> float:
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def offset(origin: Point, meters: float, bearing_deg: float) -> Point:
    """The point `meters` away from `origin` along `bearing_deg`."""
    r = 6_371_000.0
    d = meters / r
    b = math.radians(bearing_deg)
    lat1, lng1 = math.radians(origin[0]), math.radians(origin[1])
    lat2 = math.asin(math.sin(lat1) * math.cos(d) + math.cos(lat1) * math.sin(d) * math.cos(b))
    lng2 = lng1 + math.atan2(
        math.sin(b) * math.sin(d) * math.cos(lat1),
        math.cos(d) - math.sin(lat1) * math.sin(lat2),
    )
    return (math.degrees(lat2), math.degrees(lng2))


def along(points: list[Point], meters: float) -> tuple[Point, float, float]:
    """Position `meters` along the path, its heading, and the distance left to the end."""
    walked = 0.0
    for a, b in itertools.pairwise(points):
        seg = distance_m(a, b)
        if walked + seg >= meters and seg > 0:
            t = (meters - walked) / seg
            here = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            return here, bearing(a, b), total_m(points) - meters
        walked += seg
    end = points[-1]
    head = bearing(points[-2], end) if len(points) > 1 else 0.0
    return end, head, 0.0


def total_m(points: list[Point]) -> float:
    return sum(distance_m(a, b) for a, b in itertools.pairwise(points))
