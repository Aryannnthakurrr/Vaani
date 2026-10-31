"""Pre-render safety / alert phrases with Sarvam Bulbul into android/app/src/main/res/raw/.

Reads android/app/src/main/assets/phrases.json ({key: {en, hi, hinglish}}) and writes <key>_<style>.wav.
Safety speech must never wait on the network, so these clips ship inside the APK. Without
SARVAM_API_KEY this exits cleanly and the app speaks the same phrases with on-device TTS.

    SARVAM_API_KEY=... backend/.venv/Scripts/python scripts/render_alert_clips.py [--force]
"""

from __future__ import annotations

import base64
import io
import json
import os
import sys
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PHRASES = ROOT / "android/app/src/main/assets/phrases.json"
OUT = ROOT / "android/app/src/main/res/raw"
sys.path.insert(0, str(ROOT / "backend"))


def _merge(chunks: list[bytes]) -> bytes:
    if len(chunks) == 1:
        return chunks[0]
    out, frames, params = io.BytesIO(), [], None
    for chunk in chunks:
        with wave.open(io.BytesIO(chunk), "rb") as w:
            params = params or w.getparams()
            frames.append(w.readframes(w.getnframes()))
    with wave.open(out, "wb") as w:
        w.setparams(params)
        for f in frames:
            w.writeframes(f)
    return out.getvalue()


def main() -> int:
    key = os.environ.get("SARVAM_API_KEY", "").strip()
    if not key:
        print("SARVAM_API_KEY is not set: skipping. The app will speak safety phrases with on-device TTS.")
        return 0
    from sarvamai import SarvamAI

    from app.config import get_settings

    tts = get_settings().route("tts")
    client = SarvamAI(api_subscription_key=key)
    phrases: dict[str, dict[str, str]] = json.loads(PHRASES.read_text("utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    force = "--force" in sys.argv
    made = 0
    for name, styles in phrases.items():
        for style, text in styles.items():
            target = OUT / f"{name}_{style}.wav"
            if target.exists() and not force:
                continue
            resp = client.text_to_speech.convert(
                text=text,
                language_code=tts.get("languages", {}).get(style, "en-IN"),
                speaker=tts.get("speakers", {}).get(style),
                model=tts.get("model", "bulbul:v4-flash"),
                pace=float(tts.get("pace", 0.9)),
                speech_sample_rate=int(tts.get("sample_rate", 24000)),
                output_audio_codec="wav",
            )
            target.write_bytes(_merge([base64.b64decode(a) for a in resp.audios]))
            made += 1
            print(f"rendered {target.name}")
    print(f"done: {made} clip(s) written to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
