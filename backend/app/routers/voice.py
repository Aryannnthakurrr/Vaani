"""/voice/stt and /voice/tts — Sarvam first (fastest), Gemini as fallback. 503 means "use on-device speech"."""

from __future__ import annotations

import contextlib

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

from app.config import Settings
from app.deps import require_device, settings_dep
from app.schemas import ReplyStyle
from app.speech import gemini_speech, sarvam_stt, sarvam_tts
from app.speech.sarvam_stt import SpeechUnavailable

router = APIRouter(dependencies=[Depends(require_device)])
MAX_AUDIO_BYTES = 16_000 * 2 * 31 + 1024  # 30 s of 16 kHz mono 16-bit plus header slack


class TtsRequest(BaseModel):
    text: str
    reply_style: ReplyStyle = "en"
    voice: str | None = None


@router.post("/voice/stt")
async def stt(
    file: UploadFile = File(...),
    language_hint: str = Form("unknown"),
    keyterms: str = Form(""),
    settings: Settings = Depends(settings_dep),
) -> dict[str, str]:
    if not (settings.sarvam_enabled or gemini_speech.enabled(settings)):
        raise HTTPException(status_code=503, detail="speech service unavailable; use on-device speech")
    audio = await file.read(MAX_AUDIO_BYTES + 1)
    await file.close()
    if len(audio) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="audio longer than 30 seconds")
    terms = [t.strip() for t in keyterms.split(",") if t.strip()]
    try:
        result = None
        if settings.sarvam_enabled:
            with contextlib.suppress(SpeechUnavailable):
                result = await sarvam_stt.transcribe(settings, audio, language_hint, terms)
        if result is None and gemini_speech.enabled(settings):
            result = await gemini_speech.transcribe(settings, audio, language_hint)
        if result is None:
            raise SpeechUnavailable("no provider")
    except SpeechUnavailable as exc:
        raise HTTPException(status_code=503, detail="speech service unavailable") from exc
    finally:
        del audio  # audio is never stored or logged
    return {
        "transcript": result.transcript,
        "language_code": result.language_code,
        "reply_style": result.reply_style,
    }


@router.post("/voice/tts")
async def tts(req: TtsRequest, settings: Settings = Depends(settings_dep)) -> Response:
    if not (settings.sarvam_enabled or gemini_speech.enabled(settings)):
        raise HTTPException(status_code=503, detail="speech service unavailable; use on-device speech")
    if not req.text.strip() or len(req.text) > 2000:
        raise HTTPException(status_code=422, detail="text must be 1-2000 characters")
    try:
        audio = None
        if settings.sarvam_enabled:
            with contextlib.suppress(SpeechUnavailable):
                audio = await sarvam_tts.synthesize(settings, req.text, req.reply_style, req.voice)
        if audio is None and gemini_speech.enabled(settings):
            audio = await gemini_speech.synthesize(settings, req.text, req.reply_style)
        if audio is None:
            raise SpeechUnavailable("no provider")
    except SpeechUnavailable as exc:
        raise HTTPException(status_code=503, detail="speech service unavailable") from exc
    return Response(content=audio, media_type="audio/wav")
