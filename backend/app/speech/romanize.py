"""Hindi-script words in text the user reads, turned into Roman letters ("कल सुबह 9 बजे" → "Kal subah 9 baje").

Hospital calls are transcribed by Sarvam in Hindi script, so doctor names, times and fees arrived in Devanagari and the
app showed half-Hindi, half-Roman lines. English and Hinglish users get Roman letters only; Hindi users keep Devanagari.
"""

from __future__ import annotations

import asyncio
import logging
import re

from app.config import get_settings

log = logging.getLogger("saathi.romanize")
_DEVANAGARI = re.compile(r"[ऀ-ॿ]")
TIMEOUT_S = 3.0


def _sync(text: str, key: str) -> str:
    from sarvamai import SarvamAI

    resp = SarvamAI(api_subscription_key=key).text.transliterate(
        input=text, source_language_code="hi-IN", target_language_code="en-IN"
    )
    return resp.transliterated_text or text


async def romanize(text: str | None, style: str) -> str | None:
    """`text` in Roman letters for English and Hinglish users; unchanged for Hindi, or when Sarvam is unavailable."""
    if not text or style == "hi" or not _DEVANAGARI.search(text):
        return text
    settings = get_settings()
    if not settings.sarvam_enabled:
        return text
    try:
        return await asyncio.wait_for(asyncio.to_thread(_sync, text, settings.sarvam_api_key), TIMEOUT_S)
    except Exception as exc:  # never block a call over a script conversion
        log.info("romanize failed: %s", type(exc).__name__)
        return text


# Machine fields: never rewritten, even if they somehow contain Hindi script.
_KEEP = {
    "hospital_id", "call_sid", "ask_id", "id", "start", "end", "start_iso", "end_iso", "navigate_url", "phone",
    "call_phone", "status", "decision", "call_outcome", "mode", "speaker", "confidence",
}  # fmt: skip


async def romanize_data(data: object, style: str) -> object:
    """Every user-facing string inside `data` (dicts, lists) romanized for English and Hinglish users."""
    if style == "hi":
        return data
    if isinstance(data, str):
        return await romanize(data, style)
    if isinstance(data, list):
        return list(await asyncio.gather(*(romanize_data(v, style) for v in data)))
    if isinstance(data, dict):
        keys = [k for k in data if k not in _KEEP]
        values = await asyncio.gather(*(romanize_data(data[k], style) for k in keys))
        return {**data, **dict(zip(keys, values, strict=True))}
    return data
