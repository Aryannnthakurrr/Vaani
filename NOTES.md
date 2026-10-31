# NOTES — running log of decisions, deviations and verified facts

Built on 2026-10-31 from the spec (docs/SPEC.md), with no API keys. "Verified" means it was checked on this
machine; anything not verified is listed under **Gaps**.

## Build environment (this machine)

- JDK 17.0.20 (Temurin) and Gradle 8.11.1 were installed into a local toolchain folder. The Android SDK is in
  `%LOCALAPPDATA%/Android/Sdk` (platform 35, build-tools 35 and 34, emulator, `system-images;android-35;google_apis_playstore;x86_64`).
  Nothing was added to PATH or the registry. The SDK license was accepted on the user's behalf so the SDK could
  be installed.
- AVD `saathi_api35` (Pixel 7, API 35, Play Store image) boots headless with WHPX.
- The new `cmdline-tools` (v23) only accepts package paths with `/` (`platforms/android-35`) when run from
  PowerShell. The `;` form fails with "Package … not found".
- Docker is not installed. Installing it needs admin rights or a `sudo` password, so the Playwright sandbox and the
  Docker image were not built.

## Android decisions

| Topic | Decision | Why |
|---|---|---|
| Versions | AGP 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Compose BOM 2024.12.01, compileSdk/targetSdk 35 | Known-good combination. Nobody was available to fix version-resolution failures overnight; bump in Android Studio later. |
| DI | Manual (`core/AppContainer.kt`) | Simplest option with no annotation processing beyond Room |
| HTTP | OkHttp 4.12 (REST + WebSocket) | One client for both |
| Overlays | Plain Views, not Compose | ComposeView inside a Service needs lifecycle plumbing, and plain Views are lighter |
| Overlay window type | `TYPE_ACCESSIBILITY_OVERLAY` when the accessibility service is on, else `TYPE_APPLICATION_OVERLAY` | Always on top, and no overlay permission needed once accessibility is on |
| House-help app | **Pronto** `com.company.pronto` (was Urban Company) | Single-purpose house-help app: few screens from chore to booking, so fewer agent steps than Urban Company's multi-category flow. Installs on the API 35 emulator; its accessibility tree exposes clickable, labelled buttons. Covers 11 cities only. |
| UI strings | `tr(style, en, hi, hinglish)` in Kotlin, not `strings.xml` | The app language follows the user's *spoken* style (auto), not the system locale |
| Confirm gate words | The spec's list plus `booking` and `add to cart` / `कार्ट में` | §7.1 requires add-to-cart to sit behind the gate, but the §6.4 regex didn't include it |
| Direct taps on commit buttons | Turned into a yes/no question, not just refused | Safer, and better for the user. The tap still only runs after an explicit yes. |
| Unclear yes/no | Ask once more, then treat as **no** and dismiss the sheet | §6.6. The buttons stay usable until then. |
| Scroll in GUIDE | `highlight` with `gesture: swipe_up/swipe_down` draws an animated arrow | §7.2 |
| Replay mode | Settings → Developer → Demo replay. `fixtures/` ships as debug assets. | Lets you demo every flow on an emulator without the real apps |
| Agent history | The last 6 actions go to `/agent/step`. The mock picks steps by `len(history)`, so scripts must stay ≤ 7 steps. | §6.3 |
| Practo reminders | `ReminderPlanner.forAppointment` (−1 day, −2 h) plus a CalendarContract event | §7.1 |
| Exact alarms | `SCHEDULE_EXACT_ALARM` when granted, otherwise inexact alarms | §7.5 |
| Background mic (Android 14+) | `OverlayService` switches to FGS type `microphone` while listening and falls back to on-device recognition if that is refused | Android's rules on using the mic from the background |
| `scam_demo.sh` scenario 4 | Debug-only broadcast `com.saathi.app.DEBUG_SIMULATE_FOREGROUND` (extra `package`) feeds CallGuard | GPay can't be installed on the emulator without signing in |

## Package names (not yet checked with `pm list packages` on a real device)

| App | Package |
|---|---|
| Uber | `com.ubercab` |
| Amazon India | `in.amazon.mShop.android.shopping` |
| Flipkart | `com.flipkart.android` |
| Practo | `com.practo.fabric` |
| Pronto | `com.company.pronto` (checked with `pm list packages` on the emulator) |
| GPay / PhonePe / Paytm / BHIM | `com.google.android.apps.nbu.paisa.user`, `com.phonepe.app`, `net.one97.paytm`, `in.org.npci.upiapp` |
| AnyDesk / TeamViewer QS / TeamViewer / RustDesk | `com.anydesk.anydeskandroid`, `com.teamviewer.quicksupport.market`, `com.teamviewer.teamviewer.market.mobile`, `com.carriez.flutter_hbb` |

The full lists are in `android/.../util/Packages.kt`.

## Hinglish script check (Phase 1)

Not run: it needs a Sarvam key. When a key is available, render one Hinglish line both ways with
`scripts/render_alert_clips.py` and listen. If Devanagari sounds better, set `tts.transliterate_hinglish: true`
in `backend/app/models.yaml`. The screen keeps showing Roman script either way.

## Backend

### Verified model IDs (2026-10-31)

Sources: https://ai.google.dev/gemini-api/docs/models, https://ai.google.dev/gemini-api/docs/live,
https://docs.sarvam.ai/api/api-guides-tutorials/models/saaras.md, …/models/bulbul.md, and the installed SDK
signatures (`google-genai 2.34.0`, `sarvamai 0.1.41`).

| Route | Spec guess | Chosen | Why |
|---|---|---|---|
| intent_router, classify_messages, extract_appointment, digest_writer | gemini-3.1-flash-lite | `gemini-3.5-flash-lite` | Newest stable Flash-Lite |
| agent_step, link_verdict | gemini-3.5-flash | `gemini-3.8-flash` | Newest stable Flash (the docs call it the most capable Flash model) |
| agent_step_hard | gemini-3.1-pro-preview | `gemini-3.1-pro-preview` | Still the only 3.x Pro; no stable 3.x Pro yet |
| camera_live, clinic_call_live | gemini-3.1-flash-live-preview | `gemini-3.8-live` | 3.1 live preview is now "legacy"; 3.8-live is the stable default Live model |
| stt | saaras:v4 | `saaras:v4`, mode `codemix` | codemix keeps English words in Latin and Hindi in Devanagari, which is the signal `language_style.py` uses to tell `hi` from `hinglish`. `keyterms` is only accepted on v4 REST. |
| tts | bulbul:v3 | `bulbul:v4-flash` | Bulbul v3 voice-agent EOL is 2026-11-10; v4-flash is recommended for new integrations |

TTS speakers (v4-flash persona IDs, one warm voice per style): `en` → `aparna_en_companion`,
`hi` → `roopa_hi_companion`, `hinglish` → `simran_enhi_companion`. Language codes `en-IN` / `hi-IN` / `hi-IN`.
**Not verified with a real key**: which `language_code` Bulbul wants for Roman-script Hinglish with an `enhi`
speaker. The Phase 1 script check (Roman vs Devanagari) also still needs a key. Flip `tts.transliterate_hinglish`
in `models.yaml` if Devanagari sounds better.

Live API audio: input 16 kHz 16-bit little-endian PCM (`audio/pcm;rate=16000`), output 24 kHz 16-bit PCM.
JPEG frames go in via `send_realtime_input(video=Blob(...))`.

### Deviations from the spec

- **Routers merged.** `routers/agent.py` serves `/agent/step`, `/intent`, `/classify/messages`,
  `/extract/appointment` and `/digest`. `routers/welfare.py` also serves `/scam/notify_family`. The other
  routers are `health`, `links`, `voice` and `live`. `/calls/clinic` (stretch) is not implemented.
- **Rule-based fallbacks.** Rule modules (`llm/rules_intent.py`, `llm/rules_messages.py`) power mock mode. They are also the
  fallback when Gemini errors or returns invalid JSON twice, so intent, classify, extract and digest never
  hard-fail. `/agent/step` returns a kind `fail` action instead.
- **Gemini response schema.** Gemini gets a *flat* action schema (`FlatAction`) as `response_json_schema`, because
  discriminated unions are poorly supported there. The output is then validated against the strict
  discriminated union (`schemas.AgentAction`). GUIDE mode rejects any non-guide action server-side too.
- **`confirm.then` can only be `tap` or `tap_xy`.** A model can't hide `open_app` or another action behind a confirm.
- **Welfare check timing.** The job runs every 5 minutes rather than as a single cron, so each device can have
  its own `check_time` (default `WELFARE_CHECK_TIME`). Alerts go out once per device per day; every alert is
  logged in the `alerts` table.
- **`/welfare/test` and `/scam/notify_family`** return 404 until the device has called `/welfare/config`.
- **Link checker.** The rule verdict is a floor: brand impersonation, lookalikes, APK links, Safe Browsing hits
  and pages that ask for secrets are always `dangerous`, whatever the LLM says. WHOIS and Safe Browsing are
  best-effort and skipped on failure. Bare-IP hosts are `suspicious`. A URL with an `/apk` or `/download` path
  counts as an APK link.
- **Emulator SMS senders must be numeric.** `seed_inbox.sh` (via `seed_inbox.py`) maps alphanumeric headers like
  `VM-BSESDL` to stable fake numbers. The demo SMS use `Rs` rather than `₹`, which avoids encoding problems with
  `adb emu sms send`.
- **`scam_demo.sh` scenario 4** falls back to a debug broadcast when GPay isn't installed:
  `com.saathi.app.DEBUG_SIMULATE_FOREGROUND` with extra `package`. **The Android app must handle it**
  (debug builds only) by feeding that package to CallGuard as if it came to the foreground.
- **Docker build context is the repo root**, so fixtures are bundled: `docker build -f backend/Dockerfile -t saathi-backend .`
  The image sets `SANDBOX_ENABLED=1`. Locally the sandbox stays off, and Chromium is only installed with
  `INSTALL_CHROMIUM=1`.

### Gaps / not verified

- No real Gemini or Sarvam calls were made (no keys). The real paths are exercised only up to the HTTP call; a
  fake key returns `ClientError` and the code falls back cleanly.
- The Docker image wasn't built (Docker isn't installed on the build machine), so the Playwright sandbox
  (`sandbox/browser.py::sandbox_visit`) is untested.
- Neither the Telegram send nor `/start` long-polling has been tested against the real API (no token).
  Without a token, alerts are logged only, as specified.
- The Gemini Live relay (`routers/live.py::_gemini_session`) is untested without a key. The mock WebSocket flow is tested.

### Tests

`backend/.venv/Scripts/python -m pytest -q` → 79 passed. `ruff format --check` and `ruff check` are clean.
The tests cover URL checks (lookalikes, punycode, APK, IP, shortener expansion with a mocked transport, 5-hop
limit), language style, action schema (valid and invalid), welfare logic with fixed datetimes, intent routing
(including the three Phase 1 utterances), the mock classifier against `demo_sms.json`, digest, appointment
extraction, link cache, auth rejection, `/health`, `/voice/*` 503s, the welfare and scam-notify flow, the mock
camera WebSocket, and a mock agent replay of every scenario in `fixtures/agent_scripts` against
`fixtures/screens` serialized in the CONTRACTS.md format.

### Family dashboard (Phase 8 stretch)

- `GET /family/{device_id}?key=<16 hex>` serves a read-only page with no scripts, inline CSS, light/dark modes and English + Hindi labels. It refreshes every 60 s and shows the last check-in (green under 12 h, amber under 24 h, red otherwise), the daily check time, family member names (never chat IDs) and the alert log. There is no device header, because family open it in a normal browser.
- The key is HMAC-SHA256(`DASHBOARD_SECRET`, device_id), cut to 16 hex characters and compared in constant time. A wrong key and an unknown device both return 404. If the secret isn't set, a random one is made per process, so links change on restart (logged as a warning).
- `POST /welfare/config` now also returns `dashboard_path`. When `PUBLIC_BASE_URL` is set, Telegram family alerts and the `/start` reply include the full link. The alert log stores the text without the link.
- New env vars, documented in `.env.example`: `DASHBOARD_SECRET` and `PUBLIC_BASE_URL`.
- Code is in `app/family/links.py`, `app/family/page.py` and `app/routers/family.py`; tests are in `tests/test_family.py`.

## Verification log (2026-10-31, emulator `saathi_api35`, backend in MOCK mode)

| Check | Result |
|---|---|
| `./gradlew :app:assembleDebug :app:testDebugUnitTest` | ✅ APK builds; **99/99** JVM tests pass |
| `pytest` (backend) | ✅ **89/89** pass, including the family dashboard; `ruff check` clean |
| `dev_install.sh` grants all permissions and enables the accessibility and notification-listener services | ✅ App opens straight to Home. The mic bubble shows as an accessibility overlay. |
| Scam scenario 1 (`*401*` SMS) | ✅ Red full-screen alert |
| Scam scenario 2 (unknown caller + OTP during the call) | ✅ Red in-call alert. **Hang up** really ends the call (`TelecomManager.endCall`) |
| Scam scenario 3 (saved contact + OTP) | ✅ No red alert. An amber "Be careful" notice appears because a scam SMS arrived in the last 24 h (score −3 + 4 + 4 = 5) |
| Scam scenario 4 (payment app opens during the call; GPay is simulated with the debug broadcast) | ✅ Red in-call alert |
| `seed_inbox.sh` → digest | ✅ Digest cards show bills, renewal, appointment, delivery and scams. 12 of 13 SMS were sent to `/classify`; **the OTP SMS never was**, and neither were the 2 OTPs from the scam demo |
| Safety alert with the network off | ✅ Red alert from on-device rules only |
| Cab task in replay mode (typed Hinglish request) | ✅ Agent loop → confirm sheet (Haan/Nahi) → tap → "Your cab is booked…". The activity log shows each step in plain language |
| Guide mode in replay (send a photo to Aarav) | ✅ The pulsing ring lands on the recorded Share bounds. Each step is highlighted, nothing is tapped, and it ends with "Well done!" |
| Shopping camera over the backend WebSocket mock | ✅ Product card with an amber "expires soon" badge and a spoken Hinglish line |
| Welfare test button | ✅ Backend builds the family message and logs it (no Telegram token yet) |
| No API keys in the APK (`grep -r AIza`) | ✅ None |

### Bugs found by running on the emulator, now fixed
- `adb emu gsm accept` doesn't answer an *incoming* call on the phone. `scam_demo.sh` now answers with `input keyevent KEYCODE_CALL`.
- Bare SMS links (`airtel.in/pay`) were normalised to `http://` and flagged "not secure", so a real Airtel bill showed as a possible scam. Only an explicit `http://` counts now, and there's a test for it.
- On Windows, `uvicorn --reload` left the old worker serving on :8000 after a reload. Reload is now opt-in (`RELOAD=1`).
- The confirm sheet treated "the recognizer heard nothing" as "no" and dismissed itself. Now only a heard-but-unclear answer counts, so the sheet waits for the buttons (§12: confirmation sheets stay until answered).
- MRP arrived as a number (`140.0`). It now displays as `₹140`.
- Welfare text said "since a long time"; it now reads "for a long time" when there has never been a heartbeat.

### Debug helpers added
- `adb shell am broadcast -a com.saathi.app.DEBUG_SAY --es text "'Apollo jaane ke liye cab book kar do'" -p com.saathi.app` sends a typed request in place of speech (debug builds only). Useful on emulators, where the host mic is unreliable.
- `com.saathi.app.DEBUG_SIMULATE_FOREGROUND` (extra `package`) feeds CallGuard (`scam_demo.sh` scenario 4).

## Gaps (honest list)

- **Needs keys:** real Gemini agent steps, vision, Live camera; Sarvam STT/TTS and the Hinglish script check; Telegram delivery;
  rendering the safety clips (`render_alert_clips.py`). Until then speech uses Android's on-device engines.
- **Needs a real phone:** Uber/Amazon/Practo/Pronto live flows (the fixtures are hand-written, not recorded from
  the real apps); the camera on a real pack; background mic on Android 14+; highlight alignment on a device with a
  display cutout.
- **Needs Docker:** the Playwright link sandbox.
- **Not tested on device:** the Practo flow, because its `ask_user` step needs a spoken answer and the emulator has no
  usable microphone. The JVM replay test covers it.
- **Stretch (Phase 8) not started:** AI clinic call, Gemini Nano, a family dashboard.
- Voice turns that are cancelled midway still show their last caption for a moment; this is cosmetic.

## Round 2 (2026-10-31, after the first hand-off)

**Added**
- `docs/DEVELOPING.md` is now the developer guide. The original spec is saved verbatim as `docs/SPEC.md`.
- Family dashboard (Phase 8 stretch, backend): `GET /family/<device_id>?key=…`. Its content was checked as text.
  The visual render is unchecked, because Chrome on the emulator wants its ToS accepted and the browser pane
  couldn't take a screenshot.
- Typed answers: every `ask_user` shows a text box next to the voice prompt, which helps users with speech
  impairments or a phone with no usable mic. Typed "haan"/"no" also answers the confirm sheet, and
  `DEBUG_SAY` delivers its text to whatever question is open.
- UI polish:
  - Home has a gradient header with the date and live alert/update counts, colour-coded tiles and a fixed "Talk to Saathi" bar.
  - The floating bubble is hidden while Saathi itself is on screen.
  - Cards have accent stripes, icons and pill badges.
  - Digest shows summary chips, Alerts and the activity log show relative times, and onboarding shows "Step N of 9".
- Backup demo videos are in `docs/demo/` (`scripts/record_demos.sh`), and a screenshot gallery is in the README.

**Bugs found by running on the emulator, fixed**
- **Crash:** cancelling a voice turn called `SpeechRecognizer.cancel()` off the main thread, and the app died.
  It's now posted to main, and the app scope has a `CoroutineExceptionHandler`, so one failed job can't kill the app.
- **Hang:** the on-device TTS engine sometimes never calls `onDone`, and `say()` waited forever. That blocked the
  Practo flow before the calendar and reminder step. Speech now has a length-based timeout, and clips are capped
  at their duration + 3 s.
- **No appointment reminders:** the backend returns zone-less `start_iso` (`2026-10-31T16:00:00`), but Android only
  parsed offset times. `ReminderPlanner.parseStart` now accepts both, and there's a test.
- Settings language buttons are now a 2×2 grid. The camera's Hinglish "Order online" label was shortened.

**Verified on the emulator:** the full Practo flow (typed doctor choice → confirm "haan" → booked →
`/extract/appointment` called). Tests: Android **101/101**, backend **89/89**, ruff clean.

**Still unverified:** CalendarContract insert (the emulator has no calendar account) and the dashboard's visual layout.

## Round 3 (2026-10-31, after the second hand-off)

**Added**
- **Simulated AI call to the clinic** (§7.7 stretch, the "simulated call screen" option). Open it from Digest →
  "📞 Call the clinic for me".
  - Backend `POST /calls/clinic/turn` uses Gemini with `prompts/clinic_call.md`, falling back to the script in
    `app/llm/clinic_call.py`. Android has an offline copy in `LocalClinicCall`.
  - Saathi speaks its side and a teammate types the clinic's side. The summary is spoken in the user's style and
    logged in the activity log.
  - It uses the last appointment booked through Practo (saved in settings), or a demo one.
  - Tests: backend `test_calls.py`, Android `LocalClinicCallTest`.
- **Flipkart scenario** (`fixtures/screens/flipkart_add_cart`). Saying "Flipkart" sends the shopping task to
  Flipkart (if installed, or its recording in replay mode). Both replay test suites cover it.
- **Dark mode:** a night splash theme, and accent text in pills is lightened for contrast.
- **Settings:** each section is now a titled card with an icon, and apps show as "installed / not installed".
- **Accessibility audit:** a script checked every screen for tappable elements without text or a TalkBack label.
  It found none; the one hit was a text field whose label was scrolled off-screen.
- Saathi's own entries in the activity log now say "Saathi" instead of "app".

**Bugs found and fixed**
- **Hindi text split at random.** Compose sometimes sized short Hindi phrases a few pixels narrower than they
  draw, so they wrapped or even broke mid-word ("मेरी / जानकारी", "हफ़् / ते"), and it changed from run to run.
  Three fixes:
  - Typography uses `LineBreak.Simple`.
  - Titles, card text and big-button labels fill the available width.
  - The card's accent stripe is drawn instead of laid out in an intrinsic-height row, which also removed extra
    blank space at the bottom of cards.
- **Digest Hindi grammar:** "गुरुवार को तक" now reads "गुरुवार तक" (the same fix applies to Hinglish).

**Test counts:** Android 104/104, backend 95/95, ruff clean.

## Live API bring-up (2026-10-31)

Keys go in the repo-root `.env` (git-ignored). Status with the keys provided:

| Service | Status |
|---|---|
| **Gemini** | Key valid and every model ID in `models.yaml` exists for this key (`gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-3.1-pro-preview`, `gemini-3.8-live`). But every generate call returns **402 "prepayment credits are depleted"**, so routes fall back to the offline rules and `/agent/step` returns a kind `fail`. Needs credits on the key's own AI Studio project. |
| **Sarvam** | ✅ TTS (Bulbul v4-flash, persona voices) and STT (Saaras v4 codemix) work, verified by a TTS → STT round trip in EN/HI/Hinglish. The app now speaks with Bulbul (audio cached on the device). |
| **Safe Browsing** | ✅ Google's test malware URL comes back `dangerous`. |
| **Telegram** | ✅ The bot `@sorted_admin_bot` answers `getMe`, and the backend long-polls for `/start`. Real alerts need a family member to send `/start` and add the chat ID in Settings. |
| **Twilio** | Keys are stored but not used; real clinic calls are not built (the simulated call screen is). |
| **Bolna** | ✅ A real call to a verified phone completed (see "Real AI calls to the clinic over Bolna" below). |

**Fixes made while bringing this up**
- **Sarvam TTS timed out (503)** with a flat 3 s limit. The backend created a new SDK client per sentence and imported the SDK inside the first request's deadline. Now there is one cached client, warmed at startup (also used by STT), and the timeout is `4 s + 15 ms/char`, capped at 12 s (`models.yaml`). The app's wait scales the same way.
  Measured: about 1 s per short line warm, about 6 s for a 200-character digest.
- **On Windows, `http://localhost` adds about 2 s per request** (IPv6 is tried first). Use `127.0.0.1` in scripts. The emulator (`10.0.2.2`) and `adb reverse` are not affected.
- **Safety clips** are rendered with Bulbul into `res/raw` as **Ogg Vorbis**: 57 clips, 2.3 MB (WAV was 17 MB).
  - `render_alert_clips.py` now reads `.env` itself.
  - It rejects run-on clips: one Hindi clip came back 53 s long, so anything over `4 s + 0.2 s/char` is re-requested.
  - New optional extra: `pip install -e "backend[clips]"` (soundfile).
- **Offline intent rules** (used whenever Gemini is unavailable):
  - "mujhe ye photo Aarav ko bhejni hai" is now `guide`.
  - "Flipkart se biscuit mangao" now gives product "biscuit", not "Flipkart se biscuit".
  - Tests added on both sides.
- **Hinglish script check (Phase 1):** Saaras transcribes Bulbul's Roman-script Hinglish back as accurate Devanagari Hindi ("आपकी Uber book हो गई है, चार मिनट में आ रही है"), so pronunciation from Roman script is intelligible. `transliterate_hinglish` stays `false`; it still needs a human listening check.

## Real AI calls to the clinic over Twilio (2026-10-31)

- **Backend:** `app/telephony/` (TwiML builders, Twilio signature check, call sessions, REST client) and
  `routers/telephony.py`.
  - `POST /calls/clinic/real/start` asks Twilio to dial.
  - Twilio fetches each line from `/calls/twilio/voice` and posts the clinic's speech to `/calls/twilio/gather`.
    Both use `<Gather input="speech" language="en-IN">` with the Polly.Aditi voice.
  - Every turn uses the same `clinic_call_turn` brain as the practice call (Gemini, falling back to the script).
  - `GET /calls/clinic/real/{id}` returns the live transcript; `POST …/hangup` ends the call.
- **Safety:**
  - Every Twilio webhook must carry a valid `X-Twilio-Signature` (HMAC of the public URL and the form).
  - Outbound calls only go to `CALL_ALLOWED_NUMBERS`.
  - A call is capped at 6 Saathi turns, and hangs up after 2 silent replies.
  - Transcripts are kept in memory only.
- **App:** Digest → "📞 Call the clinic for me" opens a choice between a **real call** (live transcript, End call) and
  the **practice call**. It talks to the backend through its own `RealCallClient`.
- **Tests:** `tests/test_telephony.py` covers a full signed webhook conversation, silence handling, forged
  signatures, numbers not on the allow-list, missing config and hang-up.
- **Live Gemini check:** the receptionist spoke Hinglish ("aadha ghanta wait hai"). Gemini confirmed, asked the wait,
  got 30 minutes and said goodbye in Hinglish. The summary came back in the patient's language: Devanagari for `hi`,
  Roman Hindi for `hinglish`, after the prompt was tightened.
- **Fixed:** the offline script read "aadha ghanta" as 60 minutes. Hindi fractions (aadha, pauna, sava, dedh, dhai)
  are now parsed on both backend and Android.
- **Needs the user (Twilio console):** the account is a Trial with no number, no verified caller ID and $0 balance.
  Claim a number (set it as `TWILIO_FROM_NUMBER`), verify the destination phone, enable India in Voice
  geographic permissions, and make sure there is credit. Webhooks need the public HTTPS backend (`PUBLIC_BASE_URL`
  on the Droplet). Trial calls start with a Twilio notice and a "press any key" prompt.
- **Checked later:** the Twilio trial owns no number (the console's "From" number is a shared demo number) and its
  API rejects outbound calls ("trial accounts have limited parameter access"). Twilio stays as the fallback; Bolna
  below is what works.

## Real AI calls to the clinic over Bolna (2026-10-31)

- **Why Bolna:** a hosted voice agent with Indian-language speech, $5 free credit, an Indian `+91` default caller
  ID, and no webhook or public URL needed. Compared against Twilio, Plivo, Exotel, Sarvam Samvaad and Vapi.
- **Deviation from the spec:** in this mode Bolna's agent runs the conversation (its LLM, with Sarvam Saaras v4 STT
  and Bulbul v4-flash TTS), not our `clinic_call_turn` brain. The prompt carries the same goals and privacy rules.
- **Backend:** `app/telephony/bolna.py`. When `BOLNA_API_KEY` and `BOLNA_AGENT_ID` are set,
  `POST /calls/clinic/real/start` places the call through Bolna; otherwise the Twilio flow runs. The app's API is
  unchanged.
  - `GET /calls/clinic/real/{id}` polls Bolna's execution and maps its `assistant:`/`user:` transcript to the
    agent/clinic lines.
  - When the call ends, `confirmed` and `waiting_minutes` are parsed from the clinic's words with the same rules as
    the offline script (`confirmed_from`, `waiting_minutes`). The summary uses `summary_for_user`, in the patient's style.
  - `POST …/hangup` can only cancel a call that is still queued (a Bolna limit). A live call ends when the agent says
    goodbye, or after 10 s of silence (180 s cap).
  - `CALL_ALLOWED_NUMBERS` still applies.
- **Agent:** created once with `scripts/bolna_agent.py`, which prints the id. `user_data` fills `{patient_name}`,
  `{doctor}`, `{clinic}` and `{appointment_time}`. Bolna requires `temperature: 1` for its reasoning LLMs.
- **Tests:** `tests/test_bolna.py` covers queued → in progress → completed with a Hinglish summary, no-answer and
  the allow-list.
- **Live check:** a call to a verified phone completed in about 40 s. The receptionist said, in Hindi, that 11
  o'clock was not available. The agent did not reschedule; it said it would tell the patient and said goodbye. The
  result was `confirmed: false` with the Hinglish "please call them yourself" summary.
- **Gaps:**
  - Trial accounts can only call numbers verified in Bolna (User Profile → Verified Numbers).
  - Bolna keeps call transcripts and recordings on its side, while Twilio mode keeps them only in our memory.
  - The yes/no parsing reads the whole call, so "no" plus a later "yes" counts as confirmed.

### Gemini live (after credits were activated)

- **Model choice changed after measuring:** `gemini-3.8-flash` was overloaded (503s; one answer took 166 s) and rejects `thinking_level: minimal`. Routes now use `gemini-3.5-flash` / `-flash-lite` with `thinking: minimal` (about 2–3 s typical, occasionally about 10 s), and fall back to a second model once on 503/429/500 or timeout (`fallback:` in `models.yaml`).
- **Live probe:**
  - Intents work in EN/HI/Hinglish, including "photo Aarav ko bhejni hai" → guide (after a prompt fix).
  - Classify, digest, appointment extraction and the clinic call all work.
  - `agent_step` gave correct actions on every fixture:
    - Uber "Where to?" → tap.
    - Add to cart → `confirm` with price.
    - UPI PIN screen → `ask_user` to enter it themselves.
    - Prompt-injection text on screen → ignored.
- **Spec acceptance (§7.2) passed with the real key on the emulator:** guide mode in the real Settings app went swipe-up arrow → "Tap Display & touch" → "Display size and text" → Font size slider → "Well done! The text has been made bigger."
- **Fixes found on that run:**
  - The app's agent timeout went from 10 s to 25 s, plus one quiet retry.
  - Guide mode now counts a **swipe** as the user acting, not only a tap.
  - It waits for scrolling to stop before reading the screen.
  - It re-locates the target right before drawing the ring (the ring was landing one row off after a fling).
  - The Stop chip moves to the top when the target is in the lower 40% of the screen.
- **Digest dates:** Gemini called a Sunday "next Friday". Each item now gets a precomputed `when` ("tomorrow", "on Friday", "on 8 November"), and the prompt says to use it verbatim.

## House help switched to Pronto (2026-10-31)

- `Packages.HOUSE_HELP` is now `com.company.pronto`; the task, intent rules (EN/HI/Hinglish: bartan, jhadu, pocha,
  dishes, laundry), intent prompt and `house_help` replay fixtures follow. Plumber/electrician no longer route to
  house help, because Pronto only does chores.
- **No usable deep link.** On the emulator Pronto declares `pronto://`, `exp+pronto://`, `myapp://` (Expo / React
  Native) and `https://pronto.onelink.me`, but the in-app routes are unpublished, so the task opens the app and the
  agent taps through. Re-check with `adb shell dumpsys package com.company.pronto` if Pronto publishes routes.
- **Accessibility tree:** buttons are clickable ViewGroups labelled by content-desc (seen on the location screen).
  The fixtures copy that shape. The screens after login are guessed, not recorded.
- **Payment risk:** the APK bundles Juspay (`juspay://` filter), so Pronto may ask for payment before booking. The
  agent never pays, so a pay-first flow ends the task at the payment screen. Confirm on a logged-in device.
- **Confirm gate:** `schedule` is a confirm word, so if Pronto labels its later-booking choice "Schedule", tapping it
  becomes a yes/no question. Safe, but one extra question.
- **Emulator:** first launch asks for location, and the emulator defaults to California, outside Pronto's hubs. Set
  an Indian location (`adb emu geo fix 77.03 28.46` for Gurugram) before testing. Login is by phone OTP, done by hand.
- Urban Company's `com.urbanclap.urbanclap` is no longer referenced anywhere in the code.

## Gemini latency and agent-step limits (2026-10-31, afternoon)

- Same key, "Say OK", from the dev laptop: `gemini-3.5-flash-lite` 8–10 s, `gemini-3.5-flash` 17 s,
  `gemini-3.8-flash` 238 s, `gemini-3.1-pro-preview` 503 after 25 s. Ping 5 ms and a warm TLS connection ~50 ms, so the
  time is Google-side queueing (responses report `serviceTier: standard`), not the network or the backend.
- Raised `agent_step` to 20 s and `agent_step_hard` to 25 s per model in `models.yaml`, and the phone's
  `BackendClient.agentHttp` to 55 s, so a step that needs the fallback model still fits.
- Left `intent_router` at 5 s on purpose: the phone waits only 4 s (`Assistant.kt`) and then answers from
  `LocalIntentRules`, so users never sit through a slow model after speaking.

## Deep links for Uber and Pronto (2026-10-31, evening)

- **Uber now launches by deep link** when it can: `AndroidPlaceFinder` looks the destination up with Android's
  `Geocoder`, limited to ±0.3° (~33 km) around the phone's last known coarse position (`NearbyBox`), with a 3 s cap.
  The link is `uber://?action=setPickup&pickup=my_location&dropoff[...]`, so Uber opens on the ride list and the
  agent skips searching for the destination. No permission, position or match → it opens Uber as before. The task
  notes tell the agent to check the drop-off matches before choosing a ride.
- New permission **ACCESS_COARSE_LOCATION** (onboarding step "Your area"; granted by `dev_install.sh`). Only the
  place name and the search box go to the platform geocoder; nothing is logged. Replay mode never looks places up.
- **Pronto has no usable deep link.** `pronto://service/<id>` exists in the bundle, but the ids are server-side
  listing ids; `pronto://service/kitchen_cleaning` shows "Could not load listing". The task opens the app.
- **Pronto's real flow** (seen on a Galaxy S21 FE): home → "Get Instant Service" / "Schedule for Later" (when
  partners are busy it jumps to scheduling) → day chips → "Service duration" chips (0.5 hr ₹40 … 2 hr) →
  Morning/Afternoon/Evening → slot → "Add to cart". Help is booked by time, not by chore. Task notes and the
  `house_help` replay fixtures now follow this; the agent stops once the visit is in the cart and asks the user to
  pay in Pronto, because Saathi never pays. The checkout screen itself is not yet seen.
- `uiautomator dump` times out on Pronto's animated screens and leaves a stale file; Saathi's accessibility reader
  is unaffected. After testing a deep link, start Pronto with `am start --activity-clear-task` or it reopens the link.

## Self-learning skills, Live voice and Meko memory (2026-10-31)

Design and contracts: [docs/LEARNING.md](docs/LEARNING.md). The loop is modelled on the author's earlier `sorted`
project, with the elder served by voice and the family by Telegram plus the family page.

### Verified with the live Gemini key
- Live models listed with `bidiGenerateContent`: `gemini-3.8-live`, `gemini-3.8-live-extended-thinking`,
  `gemini-3.1-flash-live-preview`, `gemini-3.5-transcribe-live`. TTS: `gemini-3.8-flash-tts`,
  `gemini-3.8-flash-lite-tts`. STT: `gemini-3.5-transcribe`.
- `WS /live/voice` through the backend: "Namaste Saathi, mujhe Sector 18 jaane ke liye cab chahiye" →
  `start_task {task: book_cab, destination: Sector 18}` and a spoken Hinglish reply. "Yaad rakhna, mera
  cardiologist Dr. Mehta hai" → `remember`; a **new** session answered "Mere heart doctor ka naam kya hai?" with
  Dr. Mehta (recalled into the system prompt).
- Live latency: connect ~1.5 s, first audio ~0.9 s after a text turn (3.1-flash-live: 2.0 s / 0.7 s).
- `gemini-3.5-transcribe` returns `audio_transcription` parts, not text parts (`response.text` is empty).
- Meko MCP endpoint answers (405 to a plain GET, as expected for MCP). The adapter was tested with faked MCP
  calls only: **no `MEKO_API_KEY` yet**, so the real tool argument names are unverified.

### Deviations
- Turn-based speech keeps Sarvam first and uses Gemini as the fallback: Gemini TTS measured 2.4–3.9 s against
  0.7–1.3 s for Bulbul, and Gemini STT writes Hinglish in Devanagari. The conversation runs on Gemini Live.
- `agent_step` stays on `gemini-3.5-flash`; `gemini-3.8-flash` was overloaded and rejects minimal thinking.
- Code skills started with the intent recogniser, not the SMS `parser`: intent answers are short, have an exact
  correctness check, and are the most frequent LLM call.

### Server
- `165.245.196.218` serves `https://165-245-196-218.sslip.io` (Caddy, Let's Encrypt on the ISRG Root X1 chain).
  Docker + compose, ufw 22/80/443, `/opt/saathi`, redeploy with `bash deploy/deploy.sh`. The sandbox image blocks
  network, writes, fork bombs and memory hogs (checked on the server). See `deploy/README.md`.
- The backend container holds the Docker socket to start sandbox containers, which is root-equivalent on the
  host. Compiled code never gets the socket; a broker that only allows `docker run saathi-skill-sandbox` is the
  hardening path.
- httpx logged Telegram request URLs (bot token in the path) at INFO; httpx/httpcore/mcp/google_genai are now at
  WARNING. Rotate the bot token, since it reached the server logs once.
- Only one process may poll Telegram per bot token (409 Conflict otherwise): leave `TELEGRAM_BOT_TOKEN` empty in
  a local `.env` while the server runs.
- This development PC's network re-signs HTTPS (FortiGate), so the emulator on it cannot reach the server over
  HTTPS; use `http://10.0.2.2:8000` locally or a phone on mobile data.

### Gaps
- Meko: no key yet; `artifact_put`, `track_token_usage`, `memory_promote` and run resume are not built. Memories
  reach only the Live prompt, not `/intent` or `/agent/step`.
- Code skills: no SMS `parser` kind; each sandboxed call is a fresh `docker run` (latency not yet measured on the
  server; serving gives up after 3 s and asks the LLM).
- Live voice: no camera frames on `/live/voice` (the camera keeps `/live/camera`); not yet tried on the emulator
  with real microphone audio, only text turns through the relay.
