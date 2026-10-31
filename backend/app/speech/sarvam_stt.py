"""Sarvam Saaras speech-to-text. Audio is written to a temp file only for the SDK call and deleted."""

from __future__ import annotations

import asyncio
import logging
import os
import tempfile
from dataclasses import dataclass

from app.config import Settings
from app.speech.language_style import classify_style

log = logging.getLogger("saathi.stt")


class SpeechUnavailable(Exception):
    """Raised when Sarvam is not configured or fails; the app falls back to on-device speech."""


@dataclass
class SttResult:
    transcript: str
    language_code: str
    reply_style: str


def _transcribe_sync(settings: Settings, audio: bytes, language_hint: str, keyterms: list[str]) -> SttResult:
    from app.speech.sarvam_tts import _client

    cfg = settings.route("stt")
    client = _client(settings.sarvam_api_key)
    fd, path = tempfile.mkstemp(suffix=".wav")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(audio)
        kwargs: dict[str, object] = {
            "model": cfg.get("model", "saaras:v4"),
            "mode": cfg.get("mode", "codemix"),
        }
        if language_hint and language_hint != "unknown":
            kwargs["language_code"] = language_hint
        if cfg.get("keyterms") and keyterms and str(kwargs["model"]).endswith("v4"):
            kwargs["keyterms"] = keyterms[:50]
        with open(path, "rb") as fh:
            resp = client.speech_to_text.transcribe(file=fh, **kwargs)
    finally:
        os.unlink(path)  # never keep user audio
    transcript = (resp.transcript or "").strip()
    code = resp.language_code or language_hint or "unknown"
    return SttResult(transcript, code, classify_style(transcript, code))


async def transcribe(settings: Settings, audio: bytes, language_hint: str, keyterms: list[str]) -> SttResult:
    if not settings.sarvam_enabled:
        raise SpeechUnavailable("SARVAM_API_KEY not set")
    timeout = float(settings.route("stt").get("timeout_s", 4))
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_transcribe_sync, settings, audio, language_hint, keyterms), timeout
        )
    except Exception as exc:  # network, quota, timeout: the app falls back on device
        log.warning("stt failed: %s", type(exc).__name__)
        raise SpeechUnavailable(str(type(exc).__name__)) from exc
