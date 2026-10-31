"""Pre-render safety / alert phrases with Sarvam Bulbul into android/app/src/main/res/raw/.

Reads android/app/src/main/assets/phrases.json ({key: {en, hi, hinglish}}) and writes <key>_<style>.ogg
(Ogg Vorbis, ~10x smaller than WAV; falls back to .wav if the `soundfile` package is missing).
Safety speech must never wait on the network, so these clips ship inside the APK. Without
SARVAM_API_KEY this exits cleanly and the app speaks the same phrases with on-device TTS.
The key is read from the environment or the repo-root .env.

    backend/.venv/Scripts/python scripts/render_alert_clips.py [--force]
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


def _too_long(pcm_wav: bytes, text: str) -> bool:
    """Bulbul occasionally runs on (one clip came back as 53 s); reject clips far longer than the text."""
    with wave.open(io.BytesIO(pcm_wav), "rb") as w:
        seconds = w.getnframes() / w.getframerate()
    return seconds > 4 + 0.2 * len(text)


def _write(target_stem: Path, pcm_wav: bytes) -> Path:
    try:
        import soundfile as sf
    except ImportError:
        out = target_stem.with_suffix(".wav")
        out.write_bytes(pcm_wav)
        return out
    data, rate = sf.read(io.BytesIO(pcm_wav), dtype="int16")
    out = target_stem.with_suffix(".ogg")
    sf.write(out, data, rate, format="OGG", subtype="VORBIS")
    return out


def main() -> int:
    from app.config import get_settings

    get_settings()  # loads the repo-root .env into the environment
    key = os.environ.get("SARVAM_API_KEY", "").strip()
    if not key:
        print("SARVAM_API_KEY is not set: skipping. The app will speak safety phrases with on-device TTS.")
        return 0
    from sarvamai import SarvamAI

    tts = get_settings().route("tts")
    client = SarvamAI(api_subscription_key=key)
    phrases: dict[str, dict[str, str]] = json.loads(PHRASES.read_text("utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    force = "--force" in sys.argv
    made = 0
    for name, styles in phrases.items():
        for style, text in styles.items():
            stem = OUT / f"{name}_{style}"
            existing = [p for p in (stem.with_suffix(".ogg"), stem.with_suffix(".wav")) if p.exists()]
            if existing and not force:
                continue
            spoken = text
            if style == "hinglish" and tts.get("transliterate_hinglish"):
                spoken = client.text.transliterate(
                    input=text, source_language_code="en-IN", target_language_code="hi-IN"
                ).transliterated_text or text
            for attempt in range(3):
                resp = client.text_to_speech.convert(
                    text=spoken,
                    language_code=tts.get("languages", {}).get(style, "en-IN"),
                    speaker=tts.get("speakers", {}).get(style),
                    model=tts.get("model", "bulbul:v4-flash"),
                    pace=float(tts.get("pace", 0.9)),
                    speech_sample_rate=int(tts.get("sample_rate", 24000)),
                    output_audio_codec="wav",
                )
                pcm = _merge([base64.b64decode(a) for a in resp.audios])
                if not _too_long(pcm, text):
                    break
                print(f"  {stem.name}: clip too long, retrying ({attempt + 1})")
            for old in existing:
                old.unlink()
            written = _write(stem, pcm)
            made += 1
            print(f"rendered {written.name}")
    print(f"done: {made} clip(s) written to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
