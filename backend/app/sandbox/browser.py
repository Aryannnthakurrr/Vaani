"""Network-side link probes: shortener expansion, WHOIS age, Safe Browsing, Playwright sandbox visit.

The Playwright visit only runs when SANDBOX_ENABLED=1, which the Dockerfile sets. Never run it
against untrusted URLs on a developer laptop outside a container.
"""

from __future__ import annotations

import asyncio
import base64
import ipaddress
import logging
import socket
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlsplit

import httpx

from app.sandbox.url_checks import normalize_url

log = logging.getLogger("saathi.sandbox")
_UA = "Mozilla/5.0 (Linux; Android 14) SaathiLinkCheck/1.0"


async def is_public_url(url: str) -> bool:
    """True only when every address the host resolves to is on the public internet.

    Links come from scam messages, so the checker must never be pointed at the server's own network
    (cloud metadata at 169.254.169.254, other containers, localhost).
    """
    try:
        parts = urlsplit(url)
        host = parts.hostname
        if parts.scheme not in ("http", "https") or not host:
            return False
        infos = await asyncio.get_running_loop().getaddrinfo(host, parts.port or None, type=socket.SOCK_STREAM)
    except (OSError, ValueError, UnicodeError):
        return False
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global or ip.is_multicast:
            return False
    return bool(infos)


async def expand_url(url: str, client: httpx.AsyncClient | None = None, max_hops: int = 5) -> list[str]:
    """Follow redirects manually (HEAD, falling back to GET) without running any JavaScript."""
    chain = [normalize_url(url)]
    own = client is None
    client = client or httpx.AsyncClient(timeout=4.0, headers={"User-Agent": _UA})
    try:
        for _ in range(max_hops):
            if not await is_public_url(chain[-1]):
                log.info("expand stopped: non-public host")
                break
            try:
                resp = await client.head(chain[-1], follow_redirects=False)
                if resp.status_code in (405, 403, 501):
                    resp = await client.get(chain[-1], follow_redirects=False)
            except httpx.HTTPError as exc:
                log.info("expand stopped: %s", type(exc).__name__)
                break
            location = resp.headers.get("location")
            if not (300 <= resp.status_code < 400 and location):
                break
            chain.append(normalize_url(str(resp.url.join(location))))
    finally:
        if own:
            await client.aclose()
    return chain


def _whois_age_days_sync(domain: str) -> int | None:
    import whois  # python-whois

    record = whois.whois(domain)
    created = record.creation_date
    if isinstance(created, list):
        created = min(c for c in created if c)
    if not isinstance(created, datetime):
        return None
    if created.tzinfo is None:
        created = created.replace(tzinfo=UTC)
    return (datetime.now(UTC) - created).days


async def domain_age_days(domain: str, timeout: float = 5.0) -> int | None:
    try:
        return await asyncio.wait_for(asyncio.to_thread(_whois_age_days_sync, domain), timeout)
    except Exception as exc:  # WHOIS is best-effort: rate limits, unknown TLDs, timeouts
        log.info("whois skipped for %s: %s", domain, type(exc).__name__)
        return None


async def safe_browsing_hit(url: str, api_key: str) -> bool | None:
    if not api_key:
        return None
    body = {
        "client": {"clientId": "saathi", "clientVersion": "0.1"},
        "threatInfo": {
            "threatTypes": [
                "MALWARE",
                "SOCIAL_ENGINEERING",
                "UNWANTED_SOFTWARE",
                "POTENTIALLY_HARMFUL_APPLICATION",
            ],
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [{"url": url}],
        },
    }
    endpoint = f"https://safebrowsing.googleapis.com/v4/threatMatches:find?key={api_key}"
    try:
        async with httpx.AsyncClient(timeout=4.0) as client:
            resp = await client.post(endpoint, json=body)
            resp.raise_for_status()
            return bool(resp.json().get("matches"))
    except httpx.HTTPError as exc:
        log.info("safe browsing skipped: %s", type(exc).__name__)
        return None


async def sandbox_visit(url: str, timeout_ms: int = 10_000) -> dict[str, Any]:
    """Headless visit in a fresh context: no downloads, no form submission, no credentials."""
    from playwright.async_api import async_playwright

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True, args=["--no-sandbox"])
        try:
            context = await browser.new_context(
                accept_downloads=False, java_script_enabled=True, user_agent=_UA
            )
            # Every request the page makes (redirects, scripts, images) must go to the public internet too.
            allowed: dict[str, bool] = {}

            async def guard(route: Any) -> None:
                req_url = route.request.url
                host = urlsplit(req_url).hostname or ""
                if host not in allowed:
                    allowed[host] = await is_public_url(req_url)
                if allowed[host]:
                    await route.continue_()
                else:
                    await route.abort()

            await context.route("**/*", guard)
            page = await context.new_page()
            try:
                await page.goto(url, timeout=timeout_ms, wait_until="domcontentloaded")
            except Exception as exc:
                log.info("sandbox goto incomplete: %s", type(exc).__name__)
            fields = await page.evaluate(
                """() => {
                    const inputs = [...document.querySelectorAll('input')];
                    const txt = (i) => ((i.name||'') + ' ' + (i.id||'') + ' ' + (i.placeholder||'')).toLowerCase();
                    return {
                        password: inputs.some(i => i.type === 'password'),
                        otp: inputs.some(i => /otp|one.?time|verification/.test(txt(i))),
                        card: inputs.some(i => /card|cvv|expiry/.test(txt(i))),
                    };
                }"""
            )
            shot = await page.screenshot(type="jpeg", quality=60)
            return {
                "final_url": page.url,
                "title": await page.title(),
                "screenshot_b64": base64.b64encode(shot).decode(),
                "has_password": fields["password"],
                "has_otp": fields["otp"],
                "has_card": fields["card"],
            }
        finally:
            await browser.close()
