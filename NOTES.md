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
- Meko, verified with the real key: memories are scoped to a conversation (`memory_add`/`memory_search` require
  `conversation_id`), so each elder has one profile conversation whose id is kept locally; `metadata` must be a
  JSON string and `plan` a list; errors arrive as `{"result": "{\"error\": …}"}` text; datapack tools also need a
  conversation, so Saathi uses Meko's default datapack. **Each call takes 5-16 s**, so writes go to the local
  store at once and to Meko in the background.
- `agent_id` is now `elder-<sha256(device)[:16]>`: 8-character prefixes of two test device ids collided.
- **In Meko, the datapack is the privacy boundary, not `agent_id` or `conversation_id`.** `memory_search` returns
  everything in a datapack. With all elders in the default datapack, two new elders (distinct hashed ids, separate
  conversations, one unique fact each) each saw the other's fact. With **one datapack per elder**, neither did, and
  meaning-based search still worked inside each ("heart doctor" found "cardiologist"). So each elder gets a
  datapack named `elder-<sha256(device)[:16]>`, and `agent_id` names the part of Saathi writing into it
  (`saathi-voice` for facts, `saathi-tasks` for run trails, `saathi-setup` for the one-off conversation that
  `datapack_create` needs). Datapack and profile ids live in the local `memory_ids` table.
- Facts written before this change sit in the shared default datapack, where any elder could find them. New
  writes go to per-elder datapacks; the old shared entries should be deleted (`memory_delete_by_id`).
- A Meko search takes 15-18 s even inside a small datapack, so recall stays local on the voice path (3 s budget).

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
- Meko: `artifact_put`, `track_token_usage`, `memory_promote` and run resume are not built. Meko reads are safe
  now (per-elder datapacks), but 15-18 s searches keep them off the request path. The way to use them is to fetch
  in the background after a voice call starts and pass the results to the Live session as late context.
- Memories carry over only where they help, enforced in `app/memory` `AUDIENCES`. **Voice:** every fact.
  **Clinic calls:** only the name. **Family alerts:** the name. **`/intent`, `/agent/step`:** nothing, because both
  produce the training data for compiled skills, and varying prompt text would make the same request map to
  different answers, which breaks the held-out replay tests.
- **Facts (2026-10-31):** each has a `topic` (name, home, family, doctor, health, preference, routine, other) and
  a short English `key`. A new fact with the same topic and key replaces the old one, and `name` / `home` always
  replace. The text stays in the elder's own words and script (EN, HI, Hinglish); only the labels are English, so
  nothing depends on the language. Reads return all of an elder's facts (at most 60) instead of searching: the
  old keyword search dropped "Prefers Ola" for missing the word "preferences", and never matched Devanagari facts
  against English query words. A new `forget` voice tool deletes one fact or a whole topic, locally and in Meko
  (Meko's memory ids are kept per fact; one `memory_add` can return several). The voice prompt now gets the
  elder's name, which it never had.
- **Clinic calls can book, and say why (2026-10-31).** `purpose: book` introduces the patient by name, states the
  reason for the visit, and first asks whether the place has what the visit needs ("kalai ki fracture ki
  aftercare", ["X-ray"]). It stops if not, otherwise asks for a time inside the patient's window. The reason and
  needs come with each call request, said for that call; stored health facts are never sent. The booking needs the
  elder's yes first (`user_confirmed`), since a booking is irreversible. `place` (name, phone, facilities) is ready
  for the places search coming in a later commit; nothing fills it yet.
- Every call summary is now trilingual, including busy, no answer and could not hear (`clinic_call.CALL_NOTES`).
- Bolna booking calls: Bolna returns only a transcript, so the slot is not parsed; the patient hears the clinic's
  last words. Upgrade: pass the transcript through the clinic-call model for `has_facilities` and `slot`. Run
  `scripts/bolna_agent.py` again (it creates a new agent, so update `BOLNA_AGENT_ID`) so the agent uses `{task}`.
- Not built: no voice tool starts a booking call yet; it lands with the places search. Android still sends only
  confirm calls. Its `ignoreUnknownKeys` JSON reads the new response fields without changes.
- Code skills: no SMS `parser` kind. Each sandboxed call is a fresh `docker run`: 0.52–0.61 s on the droplet
  (measured after deploy; a memory bomb was killed in 0.7 s). Serving gives up after 3 s and asks the LLM.
- Deployed check: `wss://165-245-196-218.sslip.io/live/voice` through Caddy → `start_task book_doctor` with the
  right date and first audio in 2.2 s including the tool round trip.
- Live voice: no camera frames on `/live/voice` (the camera keeps `/live/camera`); not yet tried on the emulator
  with real microphone audio, only text turns through the relay.

## Voice-first answers for blind and low-vision users (2026-10-31, night)

Every agent message, question and confirm summary was already spoken as well as shown, and the confirm sheet
already raced voice against the Yes/No buttons. What failed people who can't see the screen was the answering:
- **One silent turn ended the listening.** `voiceYesNo` gave up after the first ~6 s of silence and `ask` after two,
  leaving only buttons or a text box. Both now use `core/voice/SpokenAnswer` (pure, `SpokenAnswerTest`): silence
  re-prompts and listens again, up to 6 silent turns (~1 minute), then Saathi says "waiting_for_tap" and the
  buttons/box stay. Unclear-twice → no is unchanged (SPEC §6.6). The buttons work the whole time.
- **No "your turn" signal.** `SpeechInput.listen(cue = true)` plays a 150 ms tone (media stream) before recording;
  the tone ends before the Vad starts, so it is never transcribed. Used for questions, yes/no and tap-to-talk.
  `waitForUserDone`'s repeated listening stays silent.
- **Spoken cues:** the confirm summary is followed by "Please say yes or no"; questions by "answer_cue";
  silence by "still_listening" / "ask_yes_no_again". New phrases in `assets/phrases.json` have no bundled clips
  yet (on-device TTS until `scripts/render_alert_clips.py` is run with a Sarvam key).
- **Slow steps:** when `/agent/step` takes over 6 s (`AgentLoop.SLOW_STEP_MS`), Saathi says "One moment" so the
  silence during Gemini's 10–20 s answers is not mistaken for a hang; a started cue finishes before the next line.
- Not done: TalkBack-specific work (live regions, focus order on the overlays) — Saathi voices its own overlays,
  and a live region would make TalkBack read every line twice. The Compose screens (Home, Settings, digest list)
  rely on TalkBack for now.

## Symptom-to-care: intake, emergency path and hospital calling (2026-10-31, night)

Built from the team's workflow spec; the design, API and setup are in [docs/CARE.md](docs/CARE.md).
- **Verified here (no keys needed):** backend `test_care.py` covers the §3.6 acceptance (11 red-flag scripts → A at
  once, 4 everyday complaints → B, the note always has EMERGENCY ASSESSMENT), negations, red flags in follow-up
  answers, slot parsing ("kal 11 baje"), ranking, and Path B end to end with fake calls (one "no", one unanswered →
  backup, booking → calendar fields). `test_care_dispatch.py` covers the dispatch request (P1/ALS, ATMIST), the mock
  API errors (400/401/404/409/503), a simulated drive to `ARRIVED_AT_SCENE` with a falling ETA, cancelling, and the
  unreachable-dispatch → call-108 fallback. Android: `CareReducerTest`. 204 backend and 121 Android tests pass.
- **Not yet run for real:** Places/Routes (no `GOOGLE_MAPS_API_KEY` yet), Bolna inquiry/booking agents (not created),
  the phone screens on a device, the siren volume, and the Leaflet map in the WebView.
- **Deviations:** Bolna instead of Twilio ConversationRelay, so answers are extracted after each call (no live
  `record_answer`, no IVR digits); polling instead of WebSockets (1 s); the calendar is written on the phone (no
  Google OAuth or `.ics`); Leaflet + OSM in a WebView instead of the Maps SDK; the mock stops at `ARRIVED_AT_SCENE`.
- **Intent change:** `care` is now the first intent rule on both sides. "call an ambulance" used to route to `guide`.
- **Safety:** `CALL_ALLOWED_NUMBERS` still gates every outbound call; "Call 108" opens the dialler and never dials by
  itself; the safety net can only raise urgency; symptoms and transcripts are never logged (only event types).
- **Gaps:** a voice "I'm OK" during the siren (the mic can't hear over it, so the screen's largest button cancels);
  the hospital leg of the ambulance simulation; deploying `mock_ambulance` to the droplet (`deploy/` doesn't run it
  yet); clips for the new spoken phrases.

## Silent speech on Samsung, slow Uber confirm (2026-10-31, night)

- **No sound:** speech used `USAGE_ASSISTANT`, which Samsung routes to a separate, hidden assistant volume (1/15 on
  the test S21 FE, media at 15/15). All speech (Speaker, Live voice, camera) now uses `Speaker.SPEECH_USAGE` =
  `USAGE_MEDIA`, so the normal volume keys control it. The siren stays on the alarm stream; in-call warnings stay on
  voice communication.
- **Slow step before the Uber confirm:** after choosing Uber Go, `gemini-3.5-flash` took 16 s, returned invalid JSON,
  took 12 s more on the retry, then timed out at 20 s on the next step, where `flash-lite` answered in 1.4 s.
  `agent_step` now tries flash-lite first (12 s limit), with flash as fallback; `agent_step_hard` is unchanged.
- **"Cab" means a car:** the Uber goal asked for the cheapest ride, which is usually Moto (a bike). It now asks for the
  cheapest car and tells the agent to ignore Moto, Bike and Auto rows.

## Doctor tile → hospital-calling demo channel (2026-10-31, night)

- The Home **Doctor** tile now starts the symptom-to-care flow instead of the Practo task (Practo stays reachable by
  voice: "book a heart doctor").
- **Demo channel** (`DEMO_MODE=1`, no `GOOGLE_MAPS_API_KEY`, no `DEMO_PHONE_MAP`): no location or map step (a default
  demo location if the phone has none); three stand-in hospitals that fit the note's specialty (`places.demo_hospitals`,
  e.g. heart centres for chest pain), all labelled "(demo)"; every call dials the first number in
  `CALL_ALLOWED_NUMBERS`. Calls to the same number run **one after another** with no backups, because one phone can't
  take three calls at once.
- The inquiry agent now also asks whether the needed consultation, tests or procedures (`{needs}`) are available.
- Bolna agents created on 2026-10-31: inquiry and booking ids are in the local `.env`.

## One voice everywhere: the hospital-call voice (2026-10-31, night)

- Users found the app's voice poor next to the Bolna call agent's. The call agent speaks with Sarvam **Bulbul
  v4-flash, speaker `aparna_hi_customer`** ("Aparna (Customer)"), Hindi, default pace (`scripts/bolna_agent.py`).
- The app now uses exactly that voice everywhere. Turn-based replies use it in every reply style (`models.yaml tts`:
  speaker `aparna_hi_customer`, `hi-IN`, pace 1.0; checked against Sarvam in EN, HI and Hinglish, 0.6–1.7 s). The
  bundled safety clips were re-rendered: 66 OGG files, 2.1 MB, including the four new prompts. The phone's speech
  cache key includes the voice, so old audio isn't replayed.
- Live conversation: Gemini Live still listens, takes turns and calls tools, but its own audio (voice "Aoede") is
  dropped. `app/speech/revoice.py` re-speaks its output transcription sentence by sentence in the Sarvam voice, and an
  interruption drops anything not yet played. `voice_live.speak_with: gemini` restores Gemini's audio. Cost: about 1 s
  more before the first words (one Sarvam call per sentence).
- Still not in this voice: camera mode (Gemini Live audio) and the phone's on-device fallback when Sarvam fails.
- The mock ambulance now simulates the hospital leg through `COMPLETED`; tracking and the phone follow it to the end.

## Twilio removed; masking for the real hospital path (2026-10-31, night)

- **All calls go through Bolna.** The Twilio branch of the clinic call (TwiML, signed webhooks, `/calls/twilio/*`),
  `app/telephony/twiml.py`, the `TWILIO_*` settings and their tests are gone. `CALL_ALLOWED_NUMBERS` moved to
  `app/telephony/calls.allowed_numbers()`, keeps its order, and still gates every dial.
- **`MASK_HOSPITAL_NUMBERS`** (default 1) is now separate from `DEMO_MODE`. Masked, every hospital call rings the test
  number: the first in `CALL_ALLOWED_NUMBERS`, or `DEMO_PHONE_MAP`. Real names and numbers stay on screen.
  `DEMO_MODE` only supplies stand-in hospitals when there is no `GOOGLE_MAPS_API_KEY`. With the key, the real path
  runs: Places search → ranking → masked Bolna calls → booking → calendar.
- **Maps key verified (2026-10-31):** Places API (New) returned 60 places for a cardiology search around Gurugram
  (36 after filtering); Routes `computeRouteMatrix` (traffic-aware) and `computeRoutes` both answered. Every ranked
  hospital was masked to the test number. Geocoding API isn't needed (the phone geocodes).
- **Live voice back to Gemini (2026-10-31):** `voice_live` now uses Gemini's own audio (`speak_with: gemini`, no
  extra delay) with the warmer **Sulafat** voice instead of Aoede. The language isn't pinned: the prompt matches
  English, Hindi or Hinglish to whatever the user just spoke. Measured first audio on `gemini-3.8-live`: 1.7–2.1 s.
  The Sarvam re-voicing stays available with `speak_with: sarvam`. Turn-based replies and clips keep the Aparna voice.

## Doctor intake on Gemini Live (2026-11-01)

- The care intake is now a real-time conversation: `CareFlow.liveIntake` opens `/live/voice?care=<session>`
  (prompt `care_voice.md`, one tool `intake_turn`). Triage, the red-flag safety net, the note and the route are
  unchanged (`triage.take_turn`, shared with `POST /care/{id}/intake`). The finished note reaches the phone as the
  phone-side tool `care_done`; Saathi finishes its closing sentence, then Path A or B continues as before.
- Falls back to the turn-based intake only if Live never connects (offline, mock backend: the server closes 4404).
- The intake speaks in the Live voice (Sulafat); Path B's yes/no and booking steps stay turn-based (Aparna).

## Batch 1 of the "smarter and safer" list (2026-11-01)

- **Ask, don't guess:** one shared `prompts/_judgement.md` block (via `{judgement}`) in the voice, care-intake,
  agent, triage and intent prompts: act on the clear reading, ask one short question only when the choices really
  differ, admit uncertainty, never re-ask.
- **Place choice:** the geocoder now returns up to 5 matches with their city. When a destination is in several
  areas (Apollo Hospital: Delhi, Faridabad), `PlaceChoice` asks which one, matches "Faridabad wala" or "doosra",
  asks once more if unclear, then admits it isn't sure and lets the agent search in the app.
- **Language:** "mujhe English nahi aati" / "speak English" (`LanguageSwitch`) saves the language, so the UI and
  replies switch for good.
- **Emergency filter:** negations now work in Devanagari and without "hai". New combination rules: thunderclap
  headache, fever with stiff neck or confusion, GI bleed, low sugar in a diabetic, fall without getting up. The
  triage prompt weighs combinations no rule covers. The countdown defaults to 15 s.
- **Shared appointment state:** care bookings now also save `lastAppointment` and set reminders, like Practo.
  "Cab to my doctor's appointment" resolves to that clinic (`AppointmentState`), and the live conversation is told
  the next appointment (config `context` → "What Saathi already knows" in its prompt).
- **Still to do, in this order:** OTP/account redaction with local TTS hand-off; real-time voice everywhere plus
  voice-wave cards for key decisions; on-device price-check guard before booking taps, with the comparison logic
  kept portable to the server; demo profiles (Settings picker), simulations and cost/bandwidth metrics; shopping
  aisle guidance and health-aware nudges; confirming an appointment mid-call (depends on Bolna support).

## Batch 2: private values stay on the phone, the phone speaks them (2026-11-01)

- **Vault:** incoming SMS/notification text is split by `PrivateSpans` (pure, JVM-tested). OTPs, account and card
  numbers, Aadhaar, PAN, and money debited, credited or left as balance go into a new Room table `private_values`
  (DB v2, a real 1→2 migration, kept 30 days). The stored message and anything sent to the cloud carry only tokens
  (`⟦P12⟧`); `Redactor` still masks the cloud copy as a second line of defence. Bill amounts stay plain, so bill
  reminders keep working. OTP and contact messages still never produce a cloud item. The duplicate check compares
  message shapes, because tokens differ on every copy.
- **Speaking:** `Speaker.say` splits tokenized text: ordinary words use the cloud voice, each value is read by
  on-device TTS (digits one by one, money in rupees), and the caption shows `••••`. The digest, reminders and every
  other spoken line get this automatically.
- **Live conversation:** two new tools. `latest_otp` returns only a token, and `speak_private(token)` makes the phone
  stop the mic, wait for Gemini's audio to finish, speak the value locally, then reopen the mic, so the cloud model
  never hears or sees it. The digest, classify and voice prompts say to copy tokens exactly and never guess.
- **Not covered:** email bodies (Saathi only sees notification previews) and values the user speaks aloud
  themselves (the prompt still says never to repeat them).

## Batch 3: tasks stay in the live conversation, voice-wave decisions (2026-11-01)

- **Real time during tasks:** a task started from the live conversation no longer pauses it and drops to turn-based
  speech. `LiveAgentUi` sends Gemini short bracket notes (`[say exactly…]`, `[ask…]`, `[read this… yes or no]`) and
  the user can talk over it. Safety is unchanged: yes/no is decided on the phone from the user's own transcript
  (`LiveVoiceSession.userTurns`, the same `SpokenAnswer.yesNo` rules) or the buttons; Gemini can't confirm anything.
  Safety phrases and private values are spoken on-device with the mic off. Tasks started from Home tiles or the
  doctor flow still use the turn-based overlay (the doctor intake already runs on Live).
- **Voice waves:** `VoiceWaveView` (overlay confirm sheet) and the `VoiceWave` composable (doctor booking card,
  ambulance countdown) move with the open mic (`Assistant.voiceLevel()`: live mic RMS, else the turn-based mic),
  with a hint that saying yes/no works as well as tapping. The bar maths is pure (`VoiceLevel`).
- **Not yet live:** the post-intake doctor decisions and the house-help/cab flows started from Home tiles.

## Batch 4: price check before a cab booking tap (2026-11-01)

- After the user's yes and before the booking tap, the phone takes a screenshot and reads it with **ML Kit text
  recognition (bundled model, on-device)**. `PriceGuard` (pure) pairs each price with the ride on its row and checks
  that the agreed price is the cheapest ride of the asked type (car unless they said auto or bike; ₹2 tolerance; a
  row's lowest price counts, so struck-through fares don't confuse it). It also flags when a cab is cheaper than the
  auto or bike they chose. A mismatch is asked once more with both prices ("Wait: Uber Mini is ₹150, cheaper than
  ₹184. Book the ₹184 one anyway?"); "no" sends the agent back to pick the cheaper ride and confirm again.
  Unreadable screens don't block. Cabs only for now; replayed fixture screens skip it.
- **Server-ready:** `backend/app/guard/price_guard.py` is a line-for-line twin with the same rules, not wired to any
  route. Both `PriceGuardTest` and `test_price_guard.py` run `fixtures/price_guard/cases.json`, so the copies can't
  drift: change them together.
- Screenshots never leave the phone and OCR text is never logged; the activity log notes only the verdict.

## Batch 5: profiles, metering, simulations (2026-11-01)

- **Demo profiles** (`fixtures/profiles/profiles.json`, Settings → Demo profile): Ramesh (diabetes, knee
  arthritis), Sunita (high blood pressure), Arjun (blind), Kamla (wheelchair, hard of hearing). A switch sets name,
  language and appointment and replays seeded messages through the normal inbox path. The profile line reaches
  the live conversation, the agent goal and the doctor intake. Cabs suggest a car for sore knees or an XL for a
  wheelchair (yes/no), and a known diabetic who is sweaty or shaky gets the low-sugar red flag.
- **Metering:** server `GET /usage` (per device: routes, bytes, Gemini calls and tokens); phone Settings →
  Developer → Data used (per feature, WebSockets included). `models.yaml pricing` starts empty, so cost is never
  guessed.
- **Simulation:** `scripts/simulate_profiles.py` → `docs/SIMULATION.md`. Offline findings: the learned cab skill
  cuts LLM steps by 38% over a month (and to zero per ride once active); message traffic is a few KB a month; **live
  voice is about 614 MB a month at 5 min/day**, because raw PCM travels as base64 JSON, so Opus between phone and
  server is the big bandwidth win. The run found the rules missed the "relative in an accident, send money, tell no
  one" scam; that rule is now added and tested. `--live` adds rules-vs-LLM agreement (costs tokens).

## Batch 6: shopping camera that guides, reads tags and nudges gently (2026-11-01)

- The camera prompt now guides people to the right aisle from the camera's point of view (left, right, ahead,
  shelves, never colours), reads shelf tags and price per kg/litre, and makes a once-per-product, kind health nudge
  from the shopper's profile and next appointment ("very salty, and your BP check is on Monday; the roasted makhana
  is right there"). It never lectures or refuses, and with no health info it says nothing about health.
- The context reaches the camera in its first WebSocket message, never the URL (URLs are logged).
- The live voice prompt has the same once-only nudge rule for food ordered online.

## Batch 7: confirming an appointment while the hospital is still on the call (2026-11-01)

- Verified against Bolna: custom function tools live at `agent_config.tasks[0].tools_config.api_tools` as
  `{"tools": [{name, key: "custom_task", description, pre_call_message, parameters}], "tools_params": {name: {method,
  url, api_token, param}}}`, with `param` a JSON string of `%(arg)s` templates. Created with
  `scripts/bolna_agent.py --mode inquiry` and read back from `GET /v2/agent/{id}`: tool, parameters, URL, token and
  prompt rule all stored. New inquiry agent is in `.env`; the old one is kept there as a comment.
- Not verified yet: a real call where the agent invokes the tool. The tool URL is `PUBLIC_BASE_URL`, currently a
  temporary trycloudflare tunnel, so **re-run `scripts/bolna_agent.py --mode inquiry` whenever the tunnel or
  server URL changes**, and update `BOLNA_INQUIRY_AGENT_ID`.
- Whether Bolna sends `api_token` as-is or adds its own "Bearer " is undocumented; the backend accepts both.
- The phone now asks the patient's name, age and phone before the inquiry calls (not after the recommendation), so
  a mid-call yes can book at once. It costs two or three questions up front.
- "Booked" is only said when the call's transcript confirms it; an unclear yes falls back to the normal
  recommendation and booking call. Answers after 20 s on the phone (25 s on the server) become "we'll call back".

- **Tunnel restarted (2026-10-31, late):** `cloudflared` (official binary in `~/.saathi-toolchain/cloudflared/`, not on
  PATH) runs a quick tunnel to `localhost:8000`; its URL is `PUBLIC_BASE_URL`. `BOLNA_TOOL_SECRET` was unset, which
  silently switched the mid-call tool off; it now has a random value. The inquiry agent was re-created and its
  `check_with_patient` tool reads back with the new URL and token. Checked: `/health` through the tunnel answers from
  outside this network (this PC's own HTTPS checks fail, see the FortiGate note), and the tool returns 401 without
  the secret and 200 with it (raw or "Bearer "). A quick tunnel dies with the process or a reboot: start it again,
  then update `PUBLIC_BASE_URL` and re-run `scripts/bolna_agent.py --mode inquiry`.

## Mid-call booking fix: Bolna drops long tool requests (2026-11-01)

- Seen on a real call (agent 57cf8926, 31 Oct 19:14): the agent called `check_with_patient`, said "one moment", then
  "I'll call back" within seconds although the patient said yes on the phone. Bolna's tool request gives up long
  before our 25 s hold. Now each request returns within `HOLD_S` (6 s) with `waiting`; the agent tells the receptionist
  the patient is answering and calls again, joining the same question (one `live_confirm` on the phone). The whole
  question stays open `WAIT_S` (40 s); the phone waits 35 s.
- The live prompt no longer carries the general "if they offer to book, say I'll call back" rule, which beat the tool.
  **Existing inquiry agents keep the old prompt: re-run `scripts/bolna_agent.py --mode inquiry` on each backend and
  update `BOLNA_INQUIRY_AGENT_ID`.**
- The on-call question is spoken with on-device TTS (`Speaker.sayQuick`) so it starts at once.
- After a booking the phone asks before adding the calendar event and the two reminders (a day and 2 h before,
  `ReminderPlanner`); the appointment itself is always saved for "cab to my appointment".
- Not reproduced: "the recommendation isn't spoken". The code speaks it (`CareFlow.recommend`); needs a phone log.

## Privacy fixes and the privacy audit (2026-11-01)

- `scripts/privacy_audit.py` → `docs/PRIVACY_AUDIT.md` + `docs/privacy_audit.json`. Measured: 0 of 26 personal values
  in server logs after 25 requests carrying them raw through 9 routes; 16 of 16 forged/cross-device requests
  blocked; 0 of 22 secrets leave the phone (`PrivacyAuditTest`, corpus `fixtures/privacy/messages.json`); 10 of 10
  links stripped as expected. The same checks run in `backend/tests/test_privacy.py`, `PrivacyTest.kt`,
  `PrivacyAuditTest.kt`.
- Found by the audit and fixed in `Redactor`: UPI IDs followed by a full stop were not masked; an account number
  repeated away from "a/c" (e.g. `?acc=` in a link) was not masked. Before: 3 of 22 secrets would leave the phone.
  Also new: any 11–18 digit run is masked (account/reference numbers; 10-digit phone numbers stay). This changes
  the old `nonLuhnLongNumberIsNotTreatedAsCard` expectation on purpose.
- Install secret `X-Saathi-Key` (core/net/DeviceAuth.kt, backend deps.device_ok): trust on first use, SHA-256 hash
  stored in `device_keys`. A device that never sent a key still works on its id (older builds); once it sends one,
  it must always. Found while doing this: `/welfare/*` and `/scam/notify_family` acted on the body's `device_id`, so
  any device could change another's family contacts; now `403` unless it is the caller.
- Links: `LinkPrivacy.strip` (phone) and `privacy_url` (server) share `fixtures/link_privacy/cases.json`. Short-link
  codes are kept so redirects still resolve; query-string scam signals (e.g. `?redirect=`) are no longer seen.
- Settings: `SafetyPolicy` stops on restricted Settings page titles and blocks taps on restricted rows; the
  `settings_dark_mode` / `settings_font_size` replays still pass.
- `SecureWindow()` (FLAG_SECURE) on digest, alerts, care, activity log and settings screens.
- Retention: `MessageDao.purgeBefore` (30 days); `agent_state` purged after 7 days; call transcripts dropped from a care
  session once booked or cancelled; `DELETE /memory`. Not done: a phone "Delete all my data" button.
- Family alerts: emergency text has no symptoms; scam summaries lose numbers (`welfare.family_safe`). Inquiry calls
  get `age_band` ("70s, F"); the booking call still gets the exact age.
- Not done from the list: on-phone-only switch (6), screen-step masking of typed fields (8), notification pre-scan
  narrowing (9), processors page (12), debug-hook release check (13), short-lived family link tokens (15b), Bolna
  recording settings (11b, not verified).


## Hospital calls listen properly; provider matching uses the whole need (2026-11-01)

- Real Bolna transcripts showed the inquiry agent answering half-sentences and being cut off by one word: endpointing
  250 → 700 ms, `number_of_words_for_interruption` 1 → 3, plus prompt rules to re-ask unclear answers and repeat slots
  and fees back. `scripts/bolna_agent.py --update` rewrites an existing agent in place (same id); both agents updated.
- The mid-call `check_with_patient` request awaited romanizing (up to 3 s) before returning; Bolna dropped it and the
  agent said "I'll call back". The `live_confirm` event is now spawned, HOLD_S 6 → 5. Call transcript lines are
  romanized in parallel.
- Ranking: the capability scorer gets facilities, conditions, acuity, timeframe and doctor gender; urgency sets the
  weights (`ranking.weights`); new `IntakeNote.preferences` (fee limit, insurance, prefer/avoid, doctor gender) from
  the triage prompt filters, adds a bonus, goes into the call's `needs`, and re-ranking pushes down hospitals missing
  a facility, over the fee limit or refusing the insurance (`CallResult.facilities_available`, `insurance_accepted`).
- Gap: preferences come only from the AI intake; the rule fallback doesn't capture them.
- Write-up for presenting: docs/HEALTHCARE_KT.md.

## Personal compiled abilities, three demo users, camera sugar/salt warnings (2026-11-01)

- `app/abilities`: per-user compiled abilities (code in `fixtures/personas/abilities/<user>/`, users, facts, cases and
  learning history in `fixtures/personas/personas.json`). Seeded and sandbox-tested on every backend start; only an
  ability that passes every case (held-out included) is active. `/intent` with `X-Saathi-Profile` tries them first.
- Users are the existing demo profiles Ramesh (diabetes), Sunita (BP) and Arjun (blind), keyed by plain profile id so
  all three laptops show the same people. Facts go through `get_memory()` under `persona:<id>`; ability state is a
  local table mirrored to Meko (`MekoMemory.save_state`, datapack `saathi-persona-<id>`).
- Fixed: the skill sandbox runner read stdin in the Windows locale, so any compiled code with Hindi text failed on
  Windows laptops. It now reads UTF-8.
- Camera: `food.py` decides sugar (diabetes) and sodium (BP) warnings from the label or a typical-values table (FSA
  thresholds); the mock camera shows Frooti to a diabetic user and Lay's to a BP user.
- Gaps: the personal abilities are seeded, not yet compiled from live voice traces; keyword triggers can catch a
  request that only sounds like an ability; Meko mirroring is unverified here (no `MEKO_API_KEY` on this laptop) and
  `datapack_list` (used when a teammate's backend already made the persona datapack) is assumed, not verified.
- Abilities do the small parts only: the LLM (`ability_reply` route) words every reply from the ability's result,
  and the camera model gets the food finding as facts for its own nudge. Only urgent results skip the model.
- Write-up: docs/PERSONAL_ABILITIES.md.
