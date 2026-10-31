"""Gemini Live replies re-spoken in the Sarvam call voice: sentence splitting, order and interruption."""

from __future__ import annotations

import asyncio
import base64
import io
import wave

from app.config import get_settings
from app.speech import revoice
from app.speech.revoice import Revoicer, split_sentences


def _wav(tag: bytes, rate: int = 24_000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(tag * 10)
    return buf.getvalue()


def test_split_sentences() -> None:
    done, rest = split_sentences("Theek hai. Main cab book karti hoon! Kahan jaana")
    assert done == ["Theek hai.", "Main cab book karti hoon!"]
    assert rest == "Kahan jaana"
    assert split_sentences("ठीक है। अभी")[0] == ["ठीक है।"]


class FakeWs:
    def __init__(self) -> None:
        self.sent: list[dict] = []

    async def send_json(self, msg: dict) -> None:
        self.sent.append(msg)


def test_revoicer_speaks_in_order_and_drops_after_interrupt(monkeypatch) -> None:
    async def fake_tts(settings, text, style, voice=None):  # type: ignore[no-untyped-def]
        await asyncio.sleep(0.01)
        return _wav(text[:2].encode())

    monkeypatch.setattr(revoice.sarvam_tts, "synthesize", fake_tts)

    async def run() -> list[bytes]:
        ws = FakeWs()
        r = Revoicer(ws, get_settings(), "hinglish")
        r.feed("Ab ")
        r.feed("chalte hain. Ok")
        r.flush()
        await asyncio.sleep(0.1)
        r.feed("Gone sentence. ")
        r.interrupt()  # the user spoke: nothing queued before this may play
        r.feed("Na sentence.")
        await asyncio.sleep(0.1)
        r.close()
        assert all(m["type"] == "audio" and m["sample_rate"] == 24_000 for m in ws.sent)
        return [base64.b64decode(m["pcm_b64"])[:2] for m in ws.sent]

    assert asyncio.run(run()) == [b"Ab", b"Ok", b"Na"]
