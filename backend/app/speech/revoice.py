"""Speaks Gemini Live's replies in the Sarvam voice used on hospital calls (models.yaml `tts`), not Gemini's own.

Gemini Live still runs the conversation (listening, turn-taking, tools); its audio is dropped and its output
transcription is re-spoken sentence by sentence, so the first words play while the rest is still being written.
An interruption drops everything not yet sent.
"""

from __future__ import annotations

import asyncio
import base64
import io
import logging
import re
import wave
from typing import Any

from app.config import Settings
from app.speech import sarvam_tts
from app.speech.sarvam_tts import SpeechUnavailable

log = logging.getLogger("saathi.revoice")
CHUNK_BYTES = 16_000  # ~1/3 s of 24 kHz 16-bit mono per message
_SENTENCE = re.compile(r"^(.+?[.!?।])(?:\s+|$)", re.DOTALL)


def pcm_from_wav(data: bytes) -> tuple[bytes, int]:
    """Raw 16-bit PCM and its sample rate from a WAV file."""
    with wave.open(io.BytesIO(data)) as w:
        return w.readframes(w.getnframes()), w.getframerate()


def split_sentences(buffer: str) -> tuple[list[str], str]:
    """Complete sentences at the front of `buffer`, and what is left over."""
    done: list[str] = []
    while (m := _SENTENCE.match(buffer)) is not None:
        done.append(m.group(1).strip())
        buffer = buffer[m.end() :]
    return [s for s in done if s], buffer


class Revoicer:
    def __init__(self, ws: Any, settings: Settings, style: str) -> None:
        self.ws, self.settings, self.style = ws, settings, style
        self.buffer = ""
        self.queue: asyncio.Queue[tuple[int, str]] = asyncio.Queue()
        self.epoch = 0  # bumped on interruption; stale sentences are skipped
        self.worker = asyncio.create_task(self._run())

    def feed(self, text: str) -> None:
        self.buffer += text
        sentences, self.buffer = split_sentences(self.buffer)
        for sentence in sentences:
            self.queue.put_nowait((self.epoch, sentence))

    def flush(self) -> None:
        if self.buffer.strip():
            self.queue.put_nowait((self.epoch, self.buffer.strip()))
        self.buffer = ""

    def interrupt(self) -> None:
        self.epoch += 1
        self.buffer = ""

    def close(self) -> None:
        self.worker.cancel()

    async def _run(self) -> None:
        while True:
            epoch, sentence = await self.queue.get()
            if epoch != self.epoch:
                continue
            try:
                pcm, rate = pcm_from_wav(await sarvam_tts.synthesize(self.settings, sentence, self.style))
            except (SpeechUnavailable, wave.Error, EOFError) as exc:
                log.warning("revoice failed: %s", type(exc).__name__)
                continue
            for i in range(0, len(pcm), CHUNK_BYTES):
                if epoch != self.epoch:
                    break
                await self.ws.send_json(
                    {
                        "type": "audio",
                        "pcm_b64": base64.b64encode(pcm[i : i + CHUNK_BYTES]).decode(),
                        "sample_rate": rate,
                    }
                )
