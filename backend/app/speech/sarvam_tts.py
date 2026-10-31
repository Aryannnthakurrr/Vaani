"""Sarvam Bulbul text-to-speech → a single WAV byte string."""

from __future__ import annotations

import asyncio
import base64
import io
import logging
import wave

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


def _synthesize_sync(settings: Settings, text: str, style: str, voice: str | None) -> bytes:
    from sarvamai import SarvamAI

    cfg = settings.route("tts")
    client = SarvamAI(api_subscription_key=settings.sarvam_api_key)
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
    timeout = float(settings.route("tts").get("timeout_s", 3))
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_synthesize_sync, settings, text, style, voice), timeout
        )
    except Exception as exc:
        log.warning("tts failed: %s", type(exc).__name__)
        raise SpeechUnavailable(type(exc).__name__) from exc
