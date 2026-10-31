"""Mock Ambulance Dispatch API (workflow §4 A3). A stand-in for a private CAD / aggregator API; never a real service.

    backend/.venv/Scripts/python -m uvicorn mock_ambulance.app:app --port 9000      # from the repo root

Auth: `X-Api-Key: $MOCK_AMBULANCE_API_KEY` (default `dev-key`). `MOCK_AMBULANCE_NO_UNITS=1`, or the header
`X-Debug-No-Units: 1`, makes creation fail with 503 NO_UNITS_AVAILABLE so the fallback UI can be shown.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

from fastapi import (
    Depends,
    FastAPI,
    Header,
    HTTPException,
    Query,
    Request,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.responses import HTMLResponse, JSONResponse

from mock_ambulance.sim import ORDER, STORE, Dispatch, now_iso, simulate

app = FastAPI(title="Mock Ambulance Dispatch API", version="1.0")
DRIVER_HTML = (Path(__file__).parent / "driver.html").read_text("utf-8")


def _key() -> str:
    return os.environ.get("MOCK_AMBULANCE_API_KEY", "dev-key")


def require_key(x_api_key: str | None = Header(default=None)) -> None:
    if x_api_key != _key():
        raise HTTPException(401, {"code": "UNAUTHORIZED", "message": "Bad or missing X-Api-Key."})


def _get(dispatch_id: str) -> Dispatch:
    d = STORE.items.get(dispatch_id)
    if d is None:
        raise HTTPException(404, {"code": "NOT_FOUND", "message": f"No dispatch {dispatch_id}."})
    return d


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "ok", "dispatches": len(STORE.items)}


@app.post("/v1/dispatch/requests", status_code=201, dependencies=[Depends(require_key)])
async def create(request: Request, x_debug_no_units: str | None = Header(default=None)) -> JSONResponse:
    try:
        body: dict[str, Any] = await request.json()
    except ValueError as exc:
        raise HTTPException(400, {"code": "VALIDATION", "message": "Body must be JSON."}) from exc
    loc = body.get("location") or {}
    if not isinstance(loc.get("lat"), (int, float)) or not isinstance(loc.get("lng"), (int, float)):
        raise HTTPException(
            400,
            {
                "code": "VALIDATION",
                "message": "location.lat and location.lng are required.",
            },
        )
    if not (body.get("caller") or {}).get("phone"):
        raise HTTPException(400, {"code": "VALIDATION", "message": "caller.phone is required."})
    if os.environ.get("MOCK_AMBULANCE_NO_UNITS") == "1" or x_debug_no_units == "1":
        raise HTTPException(
            503,
            {
                "code": "NO_UNITS_AVAILABLE",
                "message": "No ambulance is free near this location.",
            },
        )
    d = STORE.create(body)
    assignment = 20 if body.get("priority") in ("P1", "P2") else 45
    d.task = asyncio.create_task(simulate(d, assignment))
    base = str(request.base_url).rstrip("/")
    ws = base.replace("https://", "wss://").replace("http://", "ws://")
    return JSONResponse(
        status_code=201,
        content={
            "dispatch_id": d.id,
            "status": d.status,
            "created_at": d.created_at,
            "estimated_assignment_sec": assignment,
            "tracking": {
                "ws_url": f"{ws}/v1/dispatch/{d.id}/track",
                "poll_url": f"/v1/dispatch/{d.id}",
            },
        },
    )


@app.get("/v1/dispatch/{dispatch_id}", dependencies=[Depends(require_key)])
def get(dispatch_id: str) -> dict[str, Any]:
    return _get(dispatch_id).snapshot()


@app.get("/v1/dispatch/{dispatch_id}/request", dependencies=[Depends(require_key)])
def get_request(dispatch_id: str) -> dict[str, Any]:
    """The original request (ATMIST, triage note) for the crew's driver view."""
    return _get(dispatch_id).request


@app.post("/v1/dispatch/{dispatch_id}/cancel", dependencies=[Depends(require_key)])
async def cancel(dispatch_id: str, request: Request) -> dict[str, Any]:
    d = _get(dispatch_id)
    body = await request.json() if await request.body() else {}
    reason = body.get("reason", "OTHER")
    if reason not in ("PATIENT_OK", "DUPLICATE", "OTHER"):
        raise HTTPException(
            400,
            {
                "code": "VALIDATION",
                "message": "reason must be PATIENT_OK, DUPLICATE or OTHER.",
            },
        )
    if d.status == "CANCELLED":
        return {"status": "CANCELLED"}
    if d.status in ORDER and ORDER.index(d.status) >= ORDER.index("PATIENT_ON_BOARD"):
        raise HTTPException(
            409,
            {"code": "INVALID_STATE", "message": f"Cannot cancel after {d.status}."},
        )
    d.set_status("CANCELLED")
    if d.task:
        d.task.cancel()
    return {"status": "CANCELLED", "at": now_iso()}


@app.websocket("/v1/dispatch/{dispatch_id}/track")
async def track(ws: WebSocket, dispatch_id: str, key: str | None = Query(default=None)) -> None:
    """Status and location events as they happen. Browsers can't set headers on a WebSocket, so `?key=` works too."""
    if (ws.headers.get("x-api-key") or key) != _key():
        await ws.close(code=4401)
        return
    d = STORE.items.get(dispatch_id)
    if d is None:
        await ws.close(code=4404)
        return
    await ws.accept()
    queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
    d.subscribers.append(queue)
    try:
        await ws.send_json({"event": "status", "dispatch_id": d.id, "status": d.status, "vehicle": d.vehicle,
                            "crew": d.crew, "at": now_iso()})  # fmt: skip
        while True:
            await ws.send_json(await queue.get())
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        d.subscribers.remove(queue)


@app.get("/driver/{dispatch_id}", response_class=HTMLResponse)
def driver(dispatch_id: str, key: str = Query(default="")) -> str:
    """The crew's view (A6): ATMIST summary, address, route map and the full triage note."""
    if key != _key():
        raise HTTPException(401, "Add ?key=<MOCK_AMBULANCE_API_KEY> to the URL.")
    _get(dispatch_id)
    return DRIVER_HTML.replace("__DISPATCH_ID__", dispatch_id).replace("__KEY__", key)
