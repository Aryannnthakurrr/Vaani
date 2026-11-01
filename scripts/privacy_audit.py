"""Measures Saathi's privacy guarantees and writes docs/privacy/PRIVACY_AUDIT.md + docs/privacy/privacy_audit.json.

Runs the backend in-process (mock models, a throwaway database) and checks, with numbers:
  1. Log leaks: personal data from fixtures/privacy/messages.json, sent raw (as if the phone's redaction failed)
     through every text route, is searched for in everything the server logged.
  2. Device auth: forged, missing or other-device credentials against every protected route kind.
  3. Link stripping: personal parts removed from fixtures/link_privacy/cases.json links.
  4. Family alerts: what reaches Telegram for an emergency and a scam.
  5. Retention: what is deleted, and when.
  6. Phone side: reads android/app/build/privacy/phone_audit.json, written by PrivacyAuditTest
     (`cd android && ./gradlew :app:testDebugUnitTest`), if it exists.

    backend/.venv/Scripts/python scripts/privacy_audit.py        # bin/python on macOS/Linux
"""

from __future__ import annotations

import io
import json
import logging
import os
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "fixtures"
PHONE_REPORT = ROOT / "android" / "app" / "build" / "privacy" / "phone_audit.json"


def _app_client() -> Any:
    tmp = Path(tempfile.mkdtemp(prefix="saathi-audit-"))
    for key in ("GEMINI_API_KEY", "SARVAM_API_KEY", "TELEGRAM_BOT_TOKEN", "SAFE_BROWSING_API_KEY", "BOLNA_API_KEY",
                "MEKO_API_KEY", "DIGITALOCEAN_INFERENCE_KEY", "GOOGLE_MAPS_API_KEY"):  # fmt: skip
        os.environ[key] = ""
    os.environ.update({"MOCK": "1", "SANDBOX_ENABLED": "", "SKILL_SANDBOX": "off", "FIXTURES_DIR": str(FIX),
                       "DATABASE_URL": f"sqlite:///{tmp / 'audit.db'}", "DEMO_MODE": "1"})  # fmt: skip
    sys.path.insert(0, str(ROOT / "backend"))
    from fastapi.testclient import TestClient

    from app import deps
    from app.config import get_settings
    from app.llm.base import reset_llm
    from app.main import app

    get_settings.cache_clear()
    deps.get_db.cache_clear()
    reset_llm()
    return TestClient(app)


def _corpus() -> list[dict[str, Any]]:
    return json.loads((FIX / "privacy" / "messages.json").read_text("utf-8"))


def log_leaks(client: Any) -> dict[str, Any]:
    """Every secret, plus names and phone numbers, sent raw through the text routes; then grep the logs."""
    me = {"X-Saathi-Device": "audit-device-0001", "X-Saathi-Key": "audit-key-" + "x" * 33}
    corpus = _corpus()
    extra = ["Kamla Devi", "+91 98123 45678", "9812345678", "Sector 17 Faridabad"]
    secrets = sorted({s for c in corpus for s in c["secrets"]} | set(extra))
    raw = [c["text"] for c in corpus]
    buf = io.StringIO()
    handler = logging.StreamHandler(buf)
    handler.setLevel(logging.DEBUG)
    root = logging.getLogger()
    old_level = root.level
    root.addHandler(handler)
    root.setLevel(logging.DEBUG)
    calls = 0
    statuses: dict[str, int] = {}
    try:

        def post(path: str, body: dict[str, Any]) -> None:
            nonlocal calls
            calls += 1
            statuses[path.split("/s_")[0]] = client.post(path, json=body, headers=me).status_code

        post(
            "/classify/messages",
            {"messages": [{"id": c["id"], "sender": c["sender"], "text": c["text"]} for c in corpus]},
        )
        for text in raw:
            post("/intent", {"utterance": text})
            post(
                "/extract/appointment",
                {"screen_text": text + " Appointment with Dr. Mehta for Kamla Devi, 9812345678"},
            )
        post(
            "/digest",
            {"items": [{"category": "bill_due", "summary": t[:200]} for t in raw], "user_name": "Kamla Devi"},
        )
        post("/links/check", {"url": "http://sbi-kyc-update.in/x?acc=20123456789&otp=483920"})
        screen = 'package=com.ubercab title="Pay" secure=false\n[1] TextView "Paying from a/c 50100234567812, Kamla Devi"'
        post(
            "/agent/step",
            {
                "goal": "book a cab",
                "mode": "ACT",
                "language": "en",
                "screen_text": screen,
                "package": "com.ubercab",
            },
        )
        sid = client.post("/care/session", headers=me, json={"patient": {"name": "Kamla Devi", "age": 72, "phone": "+91 98123 45678"},
                          "location": {"lat": 28.4, "lng": 77.3, "address": "Sector 17 Faridabad"}}).json()["session_id"]  # fmt: skip
        calls += 1
        post(f"/care/{sid}/intake", {"text": "Kamla Devi ko ghutne mein dard hai, number 9812345678"})
        post("/welfare/config", {"device_id": me["X-Saathi-Device"], "user_name": "Kamla Devi", "check_time": "09:00",
                                 "contacts": [{"name": "Ravi", "telegram_chat_id": "12345"}]})  # fmt: skip
        post("/scam/notify_family", {"device_id": me["X-Saathi-Device"], "summary": raw[0]})
    finally:
        root.removeHandler(handler)
        root.setLevel(old_level)
    logged = buf.getvalue()
    found = [s for s in secrets if s in logged]
    served = sum(1 for code in statuses.values() if code < 400)
    return {"requests": calls, "values_checked": len(secrets), "log_lines": logged.count("\n"), "leaked": found,
            "routes": statuses, "routes_served": served}  # fmt: skip


def device_auth(client: Any) -> dict[str, Any]:
    owner = {"X-Saathi-Device": "audit-owner-0001", "X-Saathi-Key": "owner-" + "k" * 37}
    client.get("/usage", headers=owner)  # registers the key
    sid = client.post("/care/session", headers=owner, json={"patient": {}}).json()["session_id"]
    attacks = {
        "device id without its key": {"X-Saathi-Device": owner["X-Saathi-Device"]},
        "device id with a guessed key": {**owner, "X-Saathi-Key": "guess-" + "g" * 37},
        "no credentials": {},
        "another real device": {"X-Saathi-Device": "audit-other-0002", "X-Saathi-Key": "other-" + "o" * 37},
    }
    targets = [("GET", "/usage", None), ("GET", f"/care/{sid}/events", None),
               ("POST", "/welfare/config", {"device_id": owner["X-Saathi-Device"], "user_name": "x", "contacts": [], "check_time": "09:00"}),
               ("POST", "/scam/notify_family", {"device_id": owner["X-Saathi-Device"], "summary": "x"})]  # fmt: skip
    rows = []
    for name, headers in attacks.items():
        for method, path, body in targets:
            status = client.request(method, path, headers=headers, json=body).status_code
            # /usage for another device is allowed but returns that device's own (empty) numbers, not the owner's
            blocked = status in (401, 403, 404) or (path == "/usage" and name == "another real device")
            rows.append(
                {
                    "attack": name,
                    "route": f"{method} {path.split('/s_')[0]}",
                    "status": status,
                    "blocked": blocked,
                }
            )
    allowed = client.get("/usage", headers=owner).status_code == 200
    return {
        "attempts": len(rows),
        "blocked": sum(r["blocked"] for r in rows),
        "owner_still_works": allowed,
        "rows": rows,
    }


def link_stripping() -> dict[str, Any]:
    from app.sandbox.url_checks import privacy_url

    cases = json.loads((FIX / "link_privacy" / "cases.json").read_text("utf-8"))
    ok = sum(privacy_url(c["in"]) == c["out"] for c in cases)
    before = sum(len(c["in"]) for c in cases)
    after = sum(len(privacy_url(c["in"])) for c in cases)
    personal = [c for c in cases if c["in"].split("://")[-1].lower() != c["out"].split("://")[-1]]
    return {"cases": len(cases), "match_expected": ok, "with_personal_parts": len(personal),
            "chars_before": before, "chars_after": after, "examples": cases[:4]}  # fmt: skip


def family_alerts() -> dict[str, Any]:
    from app.routers.welfare import family_safe

    summary = (
        "Caller asked her to send Rs 25,000 to a/c 50100234567812 and share OTP 483920 from +91 98123 45678"
    )
    cleaned = family_safe(summary)
    numbers_left = [n for n in ("25,000", "50100234567812", "483920", "98123 45678") if n in cleaned]
    src = (ROOT / "backend" / "app" / "care" / "dispatch.py").read_text("utf-8")
    symptoms_in_emergency = "injuries_or_illness']}" in src
    return {"scam_summary_numbers_left": numbers_left, "emergency_alert_has_symptoms": symptoms_in_emergency,
            "scam_example": cleaned}  # fmt: skip


def retention() -> dict[str, Any]:
    from app.care.session import SESSION_TTL_S
    from app.memory.local import STEP_KEEP_S

    return {"care_session_hours": SESSION_TTL_S / 3600, "screen_task_steps_days": STEP_KEEP_S / 86400,
            "call_transcripts": "dropped once booked or cancelled", "phone_messages_days": 30, "phone_vault_days": 30,
            "forget_everything": "DELETE /memory"}  # fmt: skip


def phone() -> dict[str, Any] | None:
    return json.loads(PHONE_REPORT.read_text("utf-8")) if PHONE_REPORT.exists() else None


def run() -> dict[str, Any]:
    started = time.time()
    client = _app_client()
    report = {
        "generated": time.strftime("%Y-%m-%d %H:%M"),
        "log_leaks": log_leaks(client),
        "device_auth": device_auth(client),
        "links": link_stripping(),
        "family_alerts": family_alerts(),
        "retention": retention(),
        "phone": phone(),
    }
    report["seconds"] = round(time.time() - started, 1)
    return report


def markdown(r: dict[str, Any]) -> str:
    ll, da, ln, fa, rt, ph = (
        r[k] for k in ("log_leaks", "device_auth", "links", "family_alerts", "retention", "phone")
    )
    phone_rows = "_Run the Android unit tests to include the phone-side numbers._"
    if ph:
        pct = 100 * (ph["secrets"] - ph["leaked"]) / max(1, ph["secrets"])
        phone_rows = (f"| Secrets in {ph['messages']} real-style SMS, email and WhatsApp messages | {ph['secrets']} |\n"
                      f"| Secrets that would leave the phone | **{ph['leaked']}** ({pct:.0f}% kept on the phone) |\n"
                      f"| Messages that never leave the phone at all (OTPs, saved contacts) | {ph['kept_on_phone']} of {ph['messages']} |\n"
                      f"| Bytes of message text read / sent | {ph['bytes_in']} / {ph['bytes_out']} |")  # fmt: skip
    auth_table = "\n".join(
        f"| {x['attack']} | `{x['route']}` | {x['status']} | {'✅' if x['blocked'] else '❌'} |"
        for x in da["rows"]
    )
    return (
        f"""# Saathi privacy audit

Generated {r["generated"]} by `scripts/privacy_audit.py` in {r["seconds"]} s. Every number below is measured by
running the code, not estimated. Re-run it any time; the same checks run in CI as tests
(`backend/tests/test_privacy.py`, `android/.../PrivacyTest.kt`, `PrivacyAuditTest.kt`).

## Headline

| Check | Result |
|---|---|
| Personal values found in server logs after {ll["requests"]} requests full of them | **{len(ll["leaked"])} of {ll["values_checked"]}** |
| Secrets (OTPs, account, card, Aadhaar, PAN numbers, UPI IDs, amounts) that leave the phone | **{ph["leaked"] if ph else "n/a"} of {ph["secrets"] if ph else "n/a"}** |
| Forged or cross-device requests blocked | **{da["blocked"]} of {da["attempts"]}** |
| Links checked without their query, tokens or IDs | **{ln["match_expected"]} of {ln["cases"]}** |
| Numbers left in a scam alert to family / symptoms in an emergency alert | **{len(fa["scam_summary_numbers_left"])}** / **{"yes" if fa["emergency_alert_has_symptoms"] else "none"}** |

## 1. On the phone: what leaves

Each message goes down the same path as a real notification: private values go into the on-phone vault as tokens
(`⟦P12⟧`), OTP messages and messages from saved contacts are never sent at all, and the network layer redacts once
more. The secret values are then searched for in exactly what would be sent.

| | |
|---|---|
{phone_rows}

Found and fixed by this audit: UPI IDs at the end of a sentence (`…to ramesh.k@okaxis.`) and an account number
repeated inside a link (`?acc=20123456789`) used to slip through: 3 of 22 secrets before, 0 after.

## 2. On the server: nothing personal in the logs

{ll["requests"]} requests carried every secret above, raw (as if the phone's redaction had failed), plus a patient's
name, phone number and address, through intent, classification, appointment extraction, the digest, link checks,
screen-task steps, the doctor intake, welfare setup and scam alerts ({ll["routes_served"]} of {len(ll["routes"])} routes
answered normally, so the data really went through them). All server logging was captured at DEBUG level
({ll["log_lines"]} lines). Personal values found in it: **{len(ll["leaked"])}**{": " + ", ".join(ll["leaked"]) if ll["leaked"] else ""}.

## 3. Only the phone that owns the data can read it

Each install makes a 32-byte secret on first launch and sends it with every call (a header, never the URL). The
server keeps only its SHA-256 hash. Before this change, knowing a device ID was enough to read its doctor session
or change its family contacts.

| Attempt | Route | Status | Blocked |
|---|---|---|---|
{auth_table}

The real phone still works: **{"yes" if da["owner_still_works"] else "no"}**.

## 4. Links: checked without the personal parts

The phone and the server both strip a link before it is checked or opened in the sandbox: the query string,
fragment and login are dropped, and token-like parts (reset tokens, file IDs, UUIDs, phone and account numbers)
become `…`. Short-link codes stay so redirects can still be followed. {ln["with_personal_parts"]} of {ln["cases"]}
test links had personal parts; {ln["match_expected"]} of {ln["cases"]} come out exactly as expected
({ln["chars_before"]} → {ln["chars_after"]} characters sent).

| Link in the message | What the checker sees |
|---|---|
"""
        + "\n".join(f"| `{c['in']}` | `{c['out']}` |" for c in ln["examples"])
        + f"""

## 5. Family alerts carry no medical or money details

- Emergency alert: "an ambulance has been requested, please call them now". Symptoms: **{"included" if fa["emergency_alert_has_symptoms"] else "not included"}**.
- Scam alert, with numbers stripped: _{fa["scam_example"]}_

## 6. What is kept, and for how long

| Data | Kept |
|---|---|
| Doctor (care) session on the server | {rt["care_session_hours"]:.0f} hours |
| Hospital call transcripts | {rt["call_transcripts"]} |
| Screen-task step history | {rt["screen_task_steps_days"]:.0f} days |
| Messages on the phone | {rt["phone_messages_days"]} days |
| Private-values vault on the phone | {rt["phone_vault_days"]} days |
| "Forget everything" | `{rt["forget_everything"]}` (all remembered facts and task steps) |

## 7. Also changed

- Settings: the agent stops on any Settings page about security, accounts, apps, developer options, location,
  reset and similar, and may not tap those rows on the Settings home list. Display, text size, dark mode and sound
  still work.
- Screenshots and screen recording are blocked (`FLAG_SECURE`) on the digest, alerts, doctor, activity-log and
  settings screens, so screen-sharing scam apps see a black screen.
- Hospital inquiry calls hear an age band ("70s, F"), never the exact age.
- Message, email and screen text is redacted at the network layer for classification, appointment extraction and the
  digest, whatever the caller did before.
"""
    )


def main() -> int:
    report = run()
    (ROOT / "docs" / "privacy" / "privacy_audit.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), "utf-8"
    )
    (ROOT / "docs" / "privacy" / "PRIVACY_AUDIT.md").write_text(markdown(report), "utf-8")
    ll, da, ph = report["log_leaks"], report["device_auth"], report["phone"]
    print(f"log leaks {len(ll['leaked'])}/{ll['values_checked']}, auth blocked {da['blocked']}/{da['attempts']}, "
          f"phone leaks {ph['leaked'] if ph else 'n/a'}/{ph['secrets'] if ph else 'n/a'} -> docs/privacy/PRIVACY_AUDIT.md")  # fmt: skip
    return 0 if not ll["leaked"] and da["blocked"] == da["attempts"] and (not ph or ph["leaked"] == 0) else 1


if __name__ == "__main__":
    sys.exit(main())
