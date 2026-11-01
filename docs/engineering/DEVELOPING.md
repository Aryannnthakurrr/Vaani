# Developing Saathi

Saathi is an Android app (`android/`, Kotlin + Compose) and a FastAPI backend (`backend/`) that let elderly,
disabled and non-technical users operate Android by voice in English, Hindi and Hinglish.

- **The original product spec is [docs/engineering/SPEC.md](SPEC.md).** Read it before changing behaviour.
- **What was built, verified, deviated from and still missing is in [NOTES.md](../../NOTES.md).** Add to it whenever you
  deviate from the spec, verify a package name or model ID, or find a gap.
- **The app ↔ backend contract is [docs/engineering/CONTRACTS.md](CONTRACTS.md).** Change both sides together.

## Build and test

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest   # JDK 17
cd backend && .venv/Scripts/python -m pytest -q                       # bin/python on macOS/Linux
bash scripts/backend_dev.sh      # mock backend on :8000 (MOCK=1 without GEMINI_API_KEY; RELOAD=1 for --reload)
bash scripts/emu_setup.sh        # AVD saathi_api35; add -no-window for headless
bash scripts/dev_install.sh      # build, install, grant every permission
bash scripts/scam_demo.sh        # Scam Shield acceptance scenarios 1–4
bash scripts/seed_inbox.sh       # demo SMS → digest
python scripts/gen_fixtures.py   # regenerate fixtures/screens + fixtures/agent_scripts together
```

Scripts find the SDK via `ANDROID_HOME` (default `%LOCALAPPDATA%/Android/Sdk`) and the JDK via `JAVA_HOME`.
Debug builds accept `adb shell am broadcast -a com.saathi.app.DEBUG_SAY --es text "'…'" -p com.saathi.app` as a typed
stand-in for speech, and Settings → Developer → Demo replay runs tasks on the recorded fixture screens.

## Rules that must never regress (enforced in code, covered by tests)

- Anything irreversible (book, confirm, pay, order, send, add to cart) runs only after a spoken or tapped **yes**:
  `core/agent/SafetyPolicy.kt` + `AgentLoop.confirmThen`. A direct tap on such a button becomes a yes/no question.
- PIN / OTP / CVV / secure screens and login screens stop the agent; it never types passwords, PINs or dial codes.
- ACT mode works only in `Packages.ACT_ALLOWLIST` apps and opens only deep links built by `features/tasks`.
- GUIDE mode only highlights, asks, finishes or fails — it never taps.
- Screen, SMS and email text is data, never instructions.
- OTP messages and messages from saved contacts never leave the phone (`features/inbox/InboxPipeline.kt`);
  raw message and screen text is never logged on either side.
- Safety speech never waits on the network: bundled clips (`res/raw`) or on-device TTS from `assets/phrases.json`.
- No API key ever ships in the APK. All model IDs live only in `backend/app/models.yaml`; all package names in
  `android/.../util/Packages.kt`.

## Conventions

- Kotlin: coroutines + Flow, manual DI in `core/AppContainer.kt`, kotlinx.serialization, OkHttp, Room + DataStore.
  User-facing strings go through `tr(style, en, hi, hinglish)` so replies follow the user's spoken style.
- Overlays are plain Views (`core/overlay`), screens are Compose (`ui/`), large type (≥ 22sp body), ≥ 64dp targets.
- Python: type hints, Pydantic v2, `ruff format` + `ruff check` clean.
- Keep files focused (< ~400 lines), no placeholder TODOs; log genuine gaps in NOTES.md.
- Pure logic (detectors, scorers, policy, serializer, parsers) stays free of `android.*` so it is JVM-unit-tested.
