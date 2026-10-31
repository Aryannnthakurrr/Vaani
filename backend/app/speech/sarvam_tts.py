"""Sarvam Bulbul text-to-speech → a single WAV byte string."""

from __future__ import annotations

import asyncio
import base64
import io
import logging
import wave
from functools import lru_cache

from app.config import Settings
from app.speech.sarvam_stt import SpeechUnavailable

log = logging.getLogger("saathi.tts")


def merge_wavs(chunks: list[bytes]) -> bytes:
    """Bulbul may split long text into several WAVs; join them into one."""
    if len(chunks) == 1:
        return chunks[0]
    out = io.BytesIO()
    params = None
    frames: list[bytes] = []
    for chunk in chunks:
        with wave.open(io.BytesIO(chunk), "rb") as w:
            params = params or w.getparams()
            frames.append(w.readframes(w.getnframes()))
    assert params is not None
    with wave.open(out, "wb") as w:
        w.setparams(params)
        for f in frames:
            w.writeframes(f)
    return out.getvalue()


@lru_cache(maxsize=2)
def _client(api_key: str):  # noqa: ANN202 - SDK type is optional at import time
    """One SDK client per key, so HTTP connections are kept alive between sentences."""
    from sarvamai import SarvamAI

    return SarvamAI(api_subscription_key=api_key)


def warm_up(settings: Settings) -> None:
    """Import the SDK and open the client at startup instead of inside the first request's deadline."""
    if settings.sarvam_enabled:
        _client(settings.sarvam_api_key)


def timeout_for(settings: Settings, text: str) -> float:
    """Bulbul's latency grows with text length: base timeout plus ~15 ms per character, capped."""
    cfg = settings.route("tts")
    base = float(cfg.get("timeout_s", 4))
    return min(base + 0.015 * len(text), float(cfg.get("max_timeout_s", 12)))


def _synthesize_sync(settings: Settings, text: str, style: str, voice: str | None) -> bytes:
    cfg = settings.route("tts")
    client = _client(settings.sarvam_api_key)
    language = cfg.get("languages", {}).get(style, "en-IN")
    if style == "hinglish" and cfg.get("transliterate_hinglish"):
        resp = client.text.transliterate(
            input=text, source_language_code="en-IN", target_language_code="hi-IN"
        )
        text = resp.transliterated_text or text
    speaker = voice or cfg.get("speakers", {}).get(style)
    kwargs: dict[str, object] = {
        "text": text,
        "language_code": language,
        "model": cfg.get("model", "bulbul:v4-flash"),
        "pace": float(cfg.get("pace", 0.9)),
        "speech_sample_rate": int(cfg.get("sample_rate", 24000)),
        "output_audio_codec": "wav",
    }
    if speaker:
        kwargs["speaker"] = speaker
    resp = client.text_to_speech.convert(**kwargs)
    return merge_wavs([base64.b64decode(a) for a in resp.audios])


async def synthesize(settings: Settings, text: str, style: str, voice: str | None = None) -> bytes:
    if not settings.sarvam_enabled:
        raise SpeechUnavailable("SARVAM_API_KEY not set")
    timeout = timeout_for(settings, text)
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_synthesize_sync, settings, text, style, voice), timeout
        )
    except Exception as exc:
        log.warning("tts failed: %s", type(exc).__name__)
        raise SpeechUnavailable(type(exc).__name__) from exc
