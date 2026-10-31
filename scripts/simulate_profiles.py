"""Simulate a month of Saathi for each demo profile: rules ("compiled") vs LLM calls, scams caught, a learned skill,
and the data and model tokens an average user costs. Writes docs/SIMULATION.md and docs/simulation.json.

    backend/.venv/Scripts/python scripts/simulate_profiles.py            # offline, no keys, deterministic
    backend/.venv/Scripts/python scripts/simulate_profiles.py --live     # also asks the real model (costs tokens)

Measured: request sizes (sent through the real backend in-process), rule decisions, scam verdicts, and LLM calls
before/after the skills engine learns the cab routine. Estimated (and labelled so): tokens offline (characters / 4)
and live-voice bandwidth (from the audio formats). Cost appears only for models priced in models.yaml.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from app.config import get_settings
from app.llm import rules_messages
from app.llm.prompts import render
from app.schemas import MessageIn
from app.skills.compiler import RunTrace, heuristic_routine
from app.skills.engine import Config, SkillEngine
from app.skills.routine import parse_screen
from app.skills.store import SkillStore
from app.usage import METER, cost
from fastapi.testclient import TestClient

OTP = re.compile(r"(?i)\botp\b|one time password|verification code")
CHARS_PER_TOKEN = (
    4  # rough rule for English/Hinglish; Devanagari uses more tokens per character
)
LIVE_UP_BPS = 16_000 * 2 * 4 / 3  # 16 kHz 16-bit mic PCM, base64 in JSON
LIVE_DOWN_BPS = (
    24_000 * 2 * 4 / 3 * 0.4
)  # 24 kHz speech back, Saathi talks ~40% of the time
CAB_RIDES_PER_WEEK = 2
VOICE_MIN_PER_DAY = 5


def _tokens(chars: int) -> int:
    return max(1, chars // CHARS_PER_TOKEN)


def month_of_messages(profile: dict[str, Any], days: int) -> list[dict[str, str]]:
    """Each seeded message recurs weekly (bills, OTPs, debits); scams arrive once."""
    out = []
    for m in profile["messages"]:
        scammy = (
            "http" in m["text"]
            or "lottery" in m["text"].lower()
            or "bhejo" in m["text"].lower()
        )
        for week in range(1 if scammy else max(1, days // 7)):
            out.append(
                {
                    "id": f"{profile['id']}-{len(out)}",
                    "sender": m["sender"],
                    "text": m["text"],
                    "week": str(week),
                }
            )
    return out


def screens(scenario: str) -> list[str]:
    sys.path.insert(0, str(ROOT / "backend"))
    from tests.conftest import load_screens, serialize_screen

    return [serialize_screen(s) for s in load_screens(scenario)]


def cab_run(run_id: str, ui: list[str], dest: str) -> RunTrace:
    def node(screen: str, text: str) -> int:
        return next(
            n.id for n in parse_screen(screen) if text.lower() in n.label.lower()
        )

    return RunTrace(run_id, f"Book the cheapest Uber car to {dest}.", [
        {"screen": ui[0], "action": {"action": "tap", "id": node(ui[0], "Where to?")}},
        {"screen": ui[1], "action": {"action": "type", "id": 3, "text": dest}},
        {"screen": ui[2], "action": {"action": "tap", "id": node(ui[2], "Sarita Vihar")}},
        {"screen": ui[3], "action": {"action": "confirm", "summary": "Uber Go, ₹184. Book it?",
                                    "then": {"action": "tap", "id": node(ui[3], "Confirm Uber Go")}}},
        {"screen": ui[4], "action": {"action": "done", "message": "Your cab is booked."}},
    ])  # fmt: skip


class HeuristicCompiler:
    """Stands in for the strong model offline: the literal routine from one recorded run."""

    def __init__(self, run: RunTrace) -> None:
        self.run = run

    async def compile_json(self, route: str, system: str, user: str) -> tuple[str, int]:
        routine = heuristic_routine("uber_book", self.run)
        return (routine.model_dump_json(exclude_none=True) if routine else "{}"), 0


async def skill_learning(rides: int, tmp: Path) -> dict[str, Any]:
    """Cab rides through the real skills engine: LLM steps while learning, none once the routine is active."""
    ui = screens("uber_book")
    engine = SkillEngine(
        SkillStore(tmp / "skills.db"),
        Config(canary_every=0, min_runs=3, shadow_agree=2, auto_approve=True),
    )
    per_ride: list[int] = []
    for i in range(rides):
        run = cab_run(f"ride{i}", ui, "Apollo Hospital")
        calls = 0
        for step in run.steps:

            async def llm_step(a: dict[str, Any] = step["action"]) -> dict[str, Any]:
                nonlocal calls
                calls += 1
                return dict(a)

            await engine.step(device="sim-device-0001", run_id=run.run_id, task="uber_book", goal=run.goal,
                              mode="ACT", screen_text=step["screen"], llm_step=llm_step)  # fmt: skip
        await engine.outcome(run.run_id, "done")
        per_ride.append(calls)
        if engine.due() == ["uber_book"]:
            await engine.compile_task("uber_book", HeuristicCompiler(run))
    return {
        "llm_steps_per_ride": per_ride,
        "status": [s.status for s in engine.store.all()],
    }


def simulate(
    profile: dict[str, Any], days: int, client: TestClient, live_llm: Any
) -> dict[str, Any]:
    headers = {"X-Saathi-Device": f"sim-{profile['id']}-0001"}
    msgs = month_of_messages(profile, days)
    cloud = [
        m for m in msgs if not OTP.search(m["text"])
    ]  # OTP messages never leave the phone
    system_chars = len(render("classify"))
    METER.reset()
    started = time.perf_counter()
    rule = [
        rules_messages.classify_one(
            MessageIn(id=m["id"], sender=m["sender"], text=m["text"])
        )
        for m in cloud
    ]
    rules_ms = (time.perf_counter() - started) * 1000
    for i in range(0, len(cloud), 5):  # the phone batches a few at a time
        batch = cloud[i : i + 5]
        client.post("/classify/messages", headers=headers,
                    json={"messages": [{"id": m["id"], "sender": m["sender"], "text": m["text"]} for m in batch]})  # fmt: skip
    scams = [
        m for m, r in zip(cloud, rule, strict=True) if r.category == "scam_suspected"
    ]
    seeded_scams = {m["text"] for m in profile["messages"] if "http" in m["text"] or "lottery" in m["text"].lower()
                    or "bhejo" in m["text"].lower()}  # fmt: skip
    agreement = None
    if live_llm is not None:
        llm = asyncio.run(
            live_llm.classify(
                [
                    MessageIn(id=m["id"], sender=m["sender"], text=m["text"])
                    for m in cloud
                ]
            )
        )
        agreement = round(
            sum(a.category == b.category for a, b in zip(rule, llm, strict=True))
            / max(1, len(rule)),
            3,
        )
    snap = METER.snapshot(headers["X-Saathi-Device"])
    classify_bytes = snap["routes"].get(
        "POST /classify/messages", {"req_bytes": 0, "resp_bytes": 0}
    )
    batches = (len(cloud) + 4) // 5
    est_in = batches * _tokens(system_chars) + _tokens(
        sum(len(m["text"]) for m in cloud)
    )
    est_out = len(cloud) * 40
    rides = max(1, days * CAB_RIDES_PER_WEEK // 7)
    with tempfile.TemporaryDirectory() as tmp:
        learning = asyncio.run(skill_learning(rides, Path(tmp)))
    agent_chars = (
        len(render("agent_step", reply_style="en")) + 3_000
    )  # system prompt + a typical screen
    llm_steps = sum(learning["llm_steps_per_ride"])
    voice_mb = days * VOICE_MIN_PER_DAY * 60 * (LIVE_UP_BPS + LIVE_DOWN_BPS) / 1e6
    return {
        "profile": profile["name"],
        "messages": {
            "total": len(msgs),
            "sent_to_cloud": len(cloud),
            "kept_on_phone": len(msgs) - len(cloud),
            "rules_ms_total": round(rules_ms, 1),
            "llm_calls": batches,
            "bytes_up": classify_bytes["req_bytes"],
            "bytes_down": classify_bytes["resp_bytes"],
            "tokens_est": est_in + est_out,
            "rules_vs_llm_agreement": agreement,
        },
        "scams": {
            "seeded": len(seeded_scams),
            "caught_by_rules": len({m["text"] for m in scams} & seeded_scams),
        },
        "cab_rides": {
            "rides": rides,
            "llm_steps": llm_steps,
            "llm_steps_without_skill": rides * 5,
            "per_ride": learning["llm_steps_per_ride"],
            "skill_status": learning["status"],
            "tokens_est": llm_steps * (_tokens(agent_chars) + 60),
        },
        "live_voice_mb_est": round(voice_mb, 1),
    }


def report(
    results: list[dict[str, Any]], days: int, pricing: dict[str, Any], live: bool
) -> str:
    n = len(results)
    avg = lambda f: sum(f(r) for r in results) / n
    lines = [
        "# Simulation: a month of Saathi per demo profile",
        "",
        (
            f"Generated by `scripts/simulate_profiles.py` ({'live model' if live else 'offline, mock backend'}), "
            f"{days} days per profile, {CAB_RIDES_PER_WEEK} cab rides a week, {VOICE_MIN_PER_DAY} min of live voice a day."
        ),
        (
            "Measured: request bytes through the backend, rule decisions, scams caught, LLM steps with and without the "
            "learned skill. *Estimated*: tokens offline (characters ÷ 4) and live-voice data (from the audio formats)."
        ),
        "",
        "| Profile | Messages (cloud / on phone) | Classify calls | Scams caught | Cab LLM steps (with skill / without) | Est. tokens | Live voice data |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in results:
        m, c = r["messages"], r["cab_rides"]
        lines.append(f"| {r['profile']} | {m['sent_to_cloud']} / {m['kept_on_phone']} | {m['llm_calls']} | "
                     f"{r['scams']['caught_by_rules']} of {r['scams']['seeded']} | {c['llm_steps']} / "
                     f"{c['llm_steps_without_skill']} | {m['tokens_est'] + c['tokens_est']:,} | "
                     f"{r['live_voice_mb_est']} MB |")  # fmt: skip
    saved = 1 - avg(lambda r: r["cab_rides"]["llm_steps"]) / max(
        1, avg(lambda r: r["cab_rides"]["llm_steps_without_skill"])
    )
    tokens = avg(lambda r: r["messages"]["tokens_est"] + r["cab_rides"]["tokens_est"])
    rest_mb = avg(
        lambda r: (r["messages"]["bytes_up"] + r["messages"]["bytes_down"]) / 1e6
    )
    priced = cost(
        {
            "models": {
                "gemini-3.5-flash-lite": {
                    "prompt_tokens": int(tokens * 0.9),
                    "output_tokens": int(tokens * 0.1),
                }
            }
        },
        pricing,
    )
    lines += [
        "",
        "## Average user per month",
        "",
        f"- **Model tokens (estimate):** {tokens:,.0f}. "
        + (
            f"Cost ≈ ${priced['usd_total']:.4f}."
            if not priced["unpriced"]
            else "Cost: set `pricing` in `backend/app/models.yaml` to see it (prices are not guessed)."
        ),
        (
            f"- **Learned cab skill:** {saved:.0%} fewer LLM steps for cab rides once the routine is active "
            "(3 rides teach it, 2 shadow rides check it, then it runs without the model)."
        ),
        f"- **Data, excluding voice:** {rest_mb * 1000:.1f} KB a month of API traffic for messages (measured).",
        (
            f"- **Live voice:** ≈{avg(lambda r: r['live_voice_mb_est']):.0f} MB a month at {VOICE_MIN_PER_DAY} min/day, "
            "because audio travels as raw 16/24 kHz PCM in base64 JSON. It dominates bandwidth: Opus at ~16 kbit/s each "
            "way between the phone and our server (decoded there) would cut it to under a tenth."
        ),
        '- **Privacy:** OTP messages never reach the cloud (they are counted under "on phone").',
    ]
    if any(r["messages"]["rules_vs_llm_agreement"] is not None for r in results):
        lines.append(f"- **Rules vs LLM agreement on message categories:** "
                     f"{avg(lambda r: r['messages']['rules_vs_llm_agreement'] or 0):.0%}.")  # fmt: skip
    lines += [
        "",
        "## Hand-picked cases per profile (what personalisation should look like)",
        "",
    ]
    for p in json.loads((ROOT / "fixtures/profiles/profiles.json").read_text("utf-8"))[
        "profiles"
    ]:
        lines.append(f"**{p['name']}** ({', '.join(p['conditions'])})")
        for c in p["cases"]:
            lines.append(
                f"- {c.get('say') or 'Shows ' + str(c.get('show'))} → {c['expect']}"
            )
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument(
        "--live",
        action="store_true",
        help="also ask the real model (needs GEMINI_API_KEY; costs tokens)",
    )
    args = ap.parse_args()
    import os

    if not args.live:
        os.environ["MOCK"] = "1"
    get_settings.cache_clear()
    settings = get_settings()
    from app.main import app

    live_llm = None
    if args.live and not settings.mock:
        from app.llm.gemini_client import GeminiClient

        live_llm = GeminiClient(settings)
    profiles = json.loads(
        (ROOT / "fixtures/profiles/profiles.json").read_text("utf-8")
    )["profiles"]
    with TestClient(app) as client:
        results = [simulate(p, args.days, client, live_llm) for p in profiles]
    (ROOT / "docs/simulation.json").write_text(
        json.dumps(results, indent=1, ensure_ascii=False), "utf-8"
    )
    md = report(
        results, args.days, settings.models.get("pricing") or {}, live_llm is not None
    )
    (ROOT / "docs/SIMULATION.md").write_text(md + "\n", "utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
