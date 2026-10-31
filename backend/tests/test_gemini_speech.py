"""Gemini speech fallback: response parsing and PCM-to-WAV wrapping (no network)."""

from __future__ import annotations

import io
import wave
from types import SimpleNamespace as NS

from app.speech.gemini_speech import _transcript, as_wav


def test_transcript_reads_audio_transcription_and_text_parts():
    resp = NS(candidates=[NS(content=NS(parts=[
        NS(audio_transcription=NS(text="मेरा बेटा"), text=None),
        NS(audio_transcription=None, text="Delhi mein hai"),
    ]))])  # fmt: skip
    assert _transcript(resp) == "मेरा बेटा Delhi mein hai"
    assert _transcript(NS(candidates=None)) == ""


def test_as_wav_wraps_raw_pcm_and_keeps_wav():
    wav = as_wav(b"\x00\x01" * 2400)
    with wave.open(io.BytesIO(wav)) as w:
        assert (w.getframerate(), w.getnchannels(), w.getnframes()) == (24000, 1, 2400)
    assert as_wav(wav) == wav
