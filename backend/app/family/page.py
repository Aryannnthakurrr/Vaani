"""Renders the family dashboard: one self-contained HTML page (inline CSS, no scripts, no external assets)."""

from __future__ import annotations

from datetime import datetime
from html import escape
from typing import Any

from app.db import Device

OK_HOURS = 12
AMBER_HOURS = 24
MAX_ALERTS = 30

_KIND_LABELS = {
    "welfare": ("Welfare check", "हालचाल जाँच"),
    "welfare_test": ("Welfare check (test)", "हालचाल जाँच (टेस्ट)"),
    "scam": ("Scam Shield", "धोखा चेतावनी"),
}

_CSS = """
:root{--bg:#fdf7ee;--card:#fff;--ink:#1c1b1a;--muted:#5b4c41;--line:#efdfcf;--brand:#9c3d12;
--ok:#1b7f3b;--amber:#b06e00;--red:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#15120f;--card:#221d19;--ink:#f2eae3;--muted:#cdbcad;
--line:#3b2f27;--brand:#ffb68f;--ok:#8bd89d;--amber:#ffcc66;--red:#ff8a80}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);
font:20px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Devanagari",sans-serif}
main{max-width:720px;margin:0 auto;padding:20px 16px 48px}
h1{font-size:30px;margin:4px 0 2px;color:var(--brand)}h2{font-size:24px;margin:28px 0 10px}
.sub{color:var(--muted);margin:0 0 18px}.hi{display:block;color:var(--muted);font-size:.85em}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px;margin:12px 0}
.status{border-left:10px solid var(--s)}.status b{font-size:26px;color:var(--s)}
.ok{--s:var(--ok)}.amber{--s:var(--amber)}.red{--s:var(--red)}
.row{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}
.tag{font-weight:700;color:var(--brand)}.time{color:var(--muted);font-size:.9em}
ul{margin:0;padding-left:22px}.empty{color:var(--muted)}footer{margin-top:28px;color:var(--muted);font-size:16px}
"""


def humanize_age(last_ms: int | None, now: datetime) -> tuple[str, str, str]:
    """Returns (status class, English, Hindi) for the time since the last heartbeat."""
    if not last_ms:
        return "red", "No check-in yet", "अभी तक कोई जानकारी नहीं"
    hours = max(0.0, (now.timestamp() * 1000 - last_ms) / 3_600_000)
    if hours < 1:
        minutes = int(hours * 60)
        en, hi = ("Just now", "अभी-अभी") if minutes < 2 else (f"{minutes} minutes ago", f"{minutes} मिनट पहले")
    elif hours < 48:
        n = int(hours)
        en, hi = f"{n} hour{'s' if n != 1 else ''} ago", f"{n} घंटे पहले"
    else:
        n = int(hours // 24)
        en, hi = f"{n} days ago", f"{n} दिन पहले"
    status = "ok" if hours < OK_HOURS else "amber" if hours < AMBER_HOURS else "red"
    return status, en, hi


def _clock(ms: int, now: datetime) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=now.tzinfo).strftime("%d %b, %I:%M %p")


def render(device: Device, alerts: list[dict[str, Any]], now: datetime) -> str:
    name = escape(device.user_name or "Saathi user")
    status, ago_en, ago_hi = humanize_age(device.last_heartbeat_ms, now)
    headline = {
        "ok": ("Using the phone normally", "फ़ोन सामान्य रूप से चल रहा है"),
        "amber": ("Quiet for a while", "कुछ समय से फ़ोन नहीं चला"),
        "red": ("Please check on them", "कृपया हालचाल पूछ लीजिए"),
    }[status]
    seen_at = f" · {escape(_clock(device.last_heartbeat_ms, now))}" if device.last_heartbeat_ms else ""
    contacts = "".join(f"<li>{escape(str(c.get('name', '')))}</li>" for c in device.contacts if c.get("name"))
    rows = []
    for a in sorted(alerts, key=lambda r: r["created_ms"], reverse=True)[:MAX_ALERTS]:
        en, hi = _KIND_LABELS.get(a["kind"], (a["kind"], a["kind"]))
        rows.append(
            f'<div class="card"><div class="row"><span class="tag">{escape(en)} · {escape(hi)}</span>'
            f'<span class="time">{escape(_clock(a["created_ms"], now))}</span></div>'
            f"<p>{escape(a['text'])}</p></div>"
        )
    alerts_html = "".join(rows) or '<p class="empty">No alerts. · कोई चेतावनी नहीं।</p>'
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="60"><meta name="robots" content="noindex">
<title>Saathi · {name}</title><style>{_CSS}</style></head>
<body><main>
<h1>{name} ji</h1>
<p class="sub">Saathi family page · साथी परिवार पेज</p>
<div class="card status {status}"><b>{headline[0]}</b><span class="hi">{headline[1]}</span>
<p>Last used the phone: <strong>{ago_en}</strong>{seen_at}<span class="hi">आख़िरी बार फ़ोन चलाया: {ago_hi}</span></p>
<p>Daily check time: <strong>{escape(device.check_time or "12:00")}</strong>
<span class="hi">रोज़ की जाँच का समय</span></p></div>
<h2>Family · परिवार</h2>
<div class="card">{f"<ul>{contacts}</ul>" if contacts else '<p class="empty">No family added yet.</p>'}</div>
<h2>Alerts · चेतावनियाँ</h2>
{alerts_html}
<footer>Updates every minute. Saathi never shows OTPs or message contents here.
<span class="hi">यह पेज हर मिनट अपडेट होता है।</span></footer>
</main></body></html>"""
