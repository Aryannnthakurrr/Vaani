"""/links/check — cheap checks → (optional) Docker sandbox visit → verdict, cached 24 h."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends

from app.config import Settings
from app.db import Database
from app.deps import db_dep, llm_dep, require_device, settings_dep
from app.llm.base import LLMClient
from app.sandbox import browser
from app.sandbox.url_checks import analyse, privacy_url, rule_verdict, url_hash
from app.schemas import LinkCheckRequest, LinkCheckResponse

log = logging.getLogger("saathi.links")
router = APIRouter(dependencies=[Depends(require_device)])
CACHE_SECONDS = 24 * 3600
YOUNG_DOMAIN_DAYS = 30


async def check_url(url: str, settings: Settings, llm: LLMClient) -> LinkCheckResponse:
    url = privacy_url(url)  # second safety net: the phone strips links too; the sandbox opens only this
    first = analyse(url)
    chain = [first.url]
    if first.shortener:
        chain = await browser.expand_url(first.url)
    facts = analyse(chain[-1]) if len(chain) > 1 else first
    signals = list(dict.fromkeys([*first.signals, *facts.signals]))
    if len(chain) > 1:
        signals.append(f"redirects: {len(chain) - 1} hop(s) to {facts.host}")
        signals = [s for s in signals if not s.startswith("shortener")] if not facts.shortener else signals

    verdict, en, hi = rule_verdict(facts, signals)
    sandbox: dict[str, Any] | None = None
    if verdict != "dangerous" and not facts.official:
        age = await browser.domain_age_days(facts.registered_domain)
        if age is not None and age < YOUNG_DOMAIN_DAYS:
            signals.append(f"young_domain: registered {age} days ago")
        if await browser.safe_browsing_hit(facts.url, settings.safe_browsing_api_key):
            signals.append("safe_browsing: Google flags this site as harmful")
        verdict, en, hi = rule_verdict(facts, signals)
        if verdict != "dangerous" and settings.sandbox_enabled:
            try:
                sandbox = await browser.sandbox_visit(facts.url)
            except Exception as exc:
                log.warning("sandbox visit failed: %s", type(exc).__name__)
        if verdict != "dangerous":
            result = await llm.link_verdict(facts, signals, sandbox)
            verdict, en, hi = result.verdict, result.reason_simple, result.reason_simple_hi
    final_url = (sandbox or {}).get("final_url") or chain[-1]
    return LinkCheckResponse(
        url=first.url,
        final_url=final_url,
        verdict=verdict,
        reason_simple=en,  # type: ignore[arg-type]
        reason_simple_hi=hi,
        signals=signals,
    )


@router.post("/links/check", response_model=LinkCheckResponse)
async def links_check(
    req: LinkCheckRequest,
    settings: Settings = Depends(settings_dep),
    db: Database = Depends(db_dep),
    llm: LLMClient = Depends(llm_dep),
) -> LinkCheckResponse:
    key = url_hash(privacy_url(req.url))
    cached = db.cache_get(key, CACHE_SECONDS)
    if cached:
        return LinkCheckResponse(**{**cached, "cached": True})
    result = await check_url(req.url, settings, llm)
    db.cache_put(key, result.model_dump(exclude={"cached"}))
    log.info("links verdict=%s signals=%d", result.verdict, len(result.signals))
    return result
