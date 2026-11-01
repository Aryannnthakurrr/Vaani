# UI / UX audit (2026-11-01)

Everything found before the frontend revamp. Paths are relative to `android/app/src/main/java/com/saathi/app/`.
Each item: **what the user sees** — root cause. IDs are referenced by the fix plan at the bottom.

---

## A. Overlay windows (floating cards over other apps)

| ID | What the user sees | Root cause |
|---|---|---|
| A1 | Caption card ("Where do you want to go?") stays on screen forever, even on the launcher | `showCaption` called from ~15 places, `hideCaption` from ~4. Any early return (Cancel in `ask()`, missing slot, app not installed, accessibility off) skips it. `Assistant.launch()`'s `finally` resets state but not windows. `Assistant.kt:234`, `TaskRunner.kt:63-67,152-157` |
| A2 | Overlay cards drawn on top of Saathi's own screens (caption + answer card over Home; "Saathi: I am listening" over the Talking view) | `MainActivity.onResume` only hides the bubble; everything else is drawn regardless. `MainActivity.kt:84-93` |
| A3 | Same question twice: caption card at top and answer card at bottom | `OverlayAgentUi.ask/openAnswerBox` shows both. `OverlayAgentUi.kt:62,159` |
| A4 | Stop pressed during a question: answer box (with keyboard focus) or Yes/No sheet stays, buttons dead; the next typed request in the app silently vanishes | No `try/finally` around `ask()`/`confirm()`; stale `pendingText` swallows the next text. `OverlayAgentUi.kt:54-108`, `LiveAgentUi.kt:41-59` |
| A5 | New task loses its Stop chip and caption partway through, state flips to IDLE while it runs | Cancelled task's `finally` (after up to 5 s of `reportOutcome`) calls `setRunning(false)` on the shared overlay and nulls `liveUi`. `AgentLoop.kt:69-76`, `LiveTools.kt:140` |
| A6 | "Done" button disappears from the wait card as soon as anyone speaks; guide's "Your turn" becomes "Saathi"; question replaced by transcript | Live transcript collector calls `showCaption(text,"Saathi")` with no button, ignoring a running task. `Assistant.kt:112-118` |
| A7 | When Live ends mid-task, the task's caption disappears | `onLiveEnded` hides the caption unconditionally. `Assistant.kt:141` |
| A8 | No Stop chip during slot questions, app launch and page load (up to ~8 s) | `setRunning(true)` only inside `AgentLoop.run`. `Assistant.kt:234-243` |
| A9 | Failure ("I'm stuck…") flashes and vanishes; success gets a 6 s green card | `setRunning(false)` hides the caption right after `fail()`; `report()` only shows a card for `Done`. `Assistant.kt:254` |
| A10 | Stop chip greyed under the guide's dimming scrim | Highlight window added after the chip, so it sits on top. `OverlayController.kt:184-210` |
| A11 | Live connecting shows "Listening" before the mic is open; first words lost | CONNECTING mapped to LISTENING; mic starts in `onOpen`. `Assistant.kt:107-124` |

## B. Mic bubble

| ID | What the user sees | Root cause |
|---|---|---|
| B1 | Bubble always shows a live teal mic — looks like Saathi is always listening | One static `ic_mic`, no state. `OverlayViews.kt:70-79` |
| B2 | No way to tell idle / listening / speaking / working / connecting apart | No combined state flow exists. Pieces: `assistant.state`, `assistant.live → session.state`, `speaker.speaking`, `speech.listening`; care's `live`/`running` aren't flows at all |
| B3 | Tapping the bubble during a Live-started task ends the *conversation*, the task keeps going mute (each line waits ~6 s in silence) | `onMicTapped` checks `_live` before `job`. `Assistant.kt:83` |
| B4 | Notification says "Tap the orange button" — bubble is teal; English only | `OverlayService.kt:84` |
| B5 | Bubble TalkBack label and "Stop Saathi" English only; chip text fixed "■ Stop / रोको" | `OverlayViews.kt:78,82,92` |

**Target:** idle = muted mic (mic with red slash on a neutral/white disc); connecting = mic + spinner ring; listening = live teal mic with pulsing ring following voice level; Saathi speaking = sound-wave glyph; working = progress ring + tap = Stop. Driven by one `AssistantUiState` flow.

## C. Voice / speech state

| ID | What the user experiences | Root cause |
|---|---|---|
| C1 | Home tile (Cab, House help, Teach me) during an open Live conversation: two voices, two recorders, Gemini answers Saathi's prompts | `startTask/startGuide` use turn-based UI and never pause Live; `pause()` is dead code. `Assistant.kt:218`, `LiveVoiceSession.kt:152` |
| C2 | Two lines spoken at once cut each other off or stall up to 45 s with "speaking" stuck on | Single shared TTS/player, no mutex, `onStop` not handled. `Speaker.kt:179-229` |
| C3 | `speaker.stop()` leaves the voice wave and flow waiting for seconds | `MediaPlayer.stop()`/`tts.stop()` never complete the waiting coroutine. `Speaker.kt:142,190,221` |
| C4 | Live drops mid-conversation silently; user keeps talking to nothing | `LiveEvent.Error` only logged; connected-then-ended just hides caption. `LiveVoiceSession.kt:106,263`, `Assistant.kt:137` |
| C5 | Cloud STT times out → words thrown away, phone silently listens again | Falls back to a fresh on-device session on new audio. `SpeechInput.kt:90-101` |
| C6 | Mic permission missing / mic busy sounds like "I didn't understand" | `null` for both failure and silence. `SpeechInput.kt:80,124` |
| C7 | Listening indicator stays lit during upload/transcription | `_listening` covers whole `listenRaw`. `SpeechInput.kt:82-105` |
| C8 | Voice wave freezes at last loudness during private hand-off / after mic stops | `_micLevel` never reset; `voiceLevel()` ignores the speaker while a live session exists. `LiveVoiceSession.kt:215`, `Assistant.kt:49` |
| C9 | Voice wave shows flat dots when quiet — reads as a frozen loader | No idle motion. `ui/VoiceWave.kt` |
| C10 | Task questions heard over other apps may be silenced on Android 14+ | `ACTION_MIC_ON` only sent for Live and `listenOnce`, not task listens. `OverlayAgentUi.kt:62,120,134` (needs device check) |
| C11 | Unexpected exception ends a task silently | No catch around `runner.run`/`guide.run`. `Assistant.kt:234-252` |
| C12 | Voice-opened camera says the instruction twice | Assistant reply + camera's own welcome. `Assistant.kt:200`, `CameraSession.kt:51` |

## D. Home & navigation

| ID | What the user sees | Root cause |
|---|---|---|
| D1 | Doctor tile: nothing happens, then a near-empty screen | Goes through `startActivity` + `pendingRoute` instead of `nav.navigate`; then STARTING (GPS + network) shows no loader. `HomeScreen.kt:131`, `CareFlow.kt:116-130` |
| D2 | Cab / House help / Teach me give no in-app feedback; double tap cancels and restarts | `startTask` fires and forgets; `launch()` cancels previous job. `Assistant.kt:270` |
| D3 | Big bottom button means Talk, Stop task or End conversation depending on hidden state | `MicBar` / `onMicTapped`. `HomeScreen.kt:190` |
| D4 | MicBar status repeats what the Talking view says | `HomeScreen.kt:171-187` |
| D5 | Warnings tile + "Warnings" text link go to the same place | `HomeScreen.kt:127,145` |
| D6 | Home Talking view can show an old spoken line | Uses `speaker.caption` when not live. `HomeScreen.kt:113` |
| D7 | 7 identical white rows, needs scrolling; no visual anchor | Design |
| D8 | Double taps push duplicate screens (Start using Saathi, Run setup again, Call the clinic) | `navigate` without `launchSingleTop`. `MainActivity.kt:68-73` |
| D9 | After "Run setup again", Back goes Home → Settings → Home | `popUpTo("onboarding")` only. `MainActivity.kt:68` |
| D10 | Check-in "Thank you…" spoken again after rotation/font change, and over-claims "I told your family" | `handle(intent)` on every `onCreate`; `checkIn()` only sends a heartbeat. `MainActivity.kt:46,96-112` |
| D11 | Screens don't react to a language change | `c.settings.current` read once in composition (Care, Emergency, others) |

## E. Doctor (care) flow

| ID | What the user sees | Root cause |
|---|---|---|
| E1 | Blank during STARTING / THINKING / SEARCHING | No per-phase progress UI. `CareScreen.kt` |
| E2 | "Speak freely, I'm listening" right above "Speak, or type here" | Two hints for the same thing. `CareScreen.kt:84-90,140` |
| E3 | After "No" to the recommendation: Book / Other times / No thanks stay, all dead | Flow ended, deferreds null, phase stays RECOMMEND. `CareFlow.kt:346-353` |
| E4 | Yes/No buttons stay during the booking call after picking another slot | `_asking` not reset. `CareFlow.kt:348-350` |
| E5 | Second booking reuses the previous patient's name/age/phone without asking | `patient` cached on the flow instance. `CareFlow.kt:391-395` |
| E6 | "No" to "Shall I find you a doctor instead?" → silence, stuck screen | No else branch. `CareFlow.kt:103` |
| E7 | Back stack care → emergency → care; closing one auto-restarts a new intake | `offerDoctor` pushes via `openScreen`; old entry's `LaunchedEffect` restarts. `CareFlow.kt:97-104`, `CareScreen.kt:70` |
| E8 | Hindi mode shows English weekday/month and "h:mm a"; hospital card forces English | `CareText.time`, `CareScreen.kt:195` |
| E9 | Double start possible (tile + screen's own `LaunchedEffect`) | `HomeScreen.kt:131`, `CareScreen.kt:70` |

## F. Emergency (highest stakes)

| ID | What the user sees | Root cause |
|---|---|---|
| F1 | Siren starts *after* the user tapped "I'm OK", plays over the next screen | `siren.start()` after a suspending `say()` with no phase check. `EmergencyFlow.kt:97-100` |
| F2 | "Call 108" during countdown: dialer opens but ambulance still auto-dispatches at 0 | `call108()` only stops the siren. `EmergencyFlow.kt:235` |
| F3 | Back gesture during countdown/tracking: siren continues, auto-dispatch continues, announcements over other screens | No BackHandler / onDispose. `EmergencyScreen.kt:73` |
| F4 | "Cancelled. No ambulance is coming." even if the cancel request failed | Phase set before request; failure swallowed. `EmergencyFlow.kt:228-231` |
| F5 | Cancelled screen flashes and is replaced by Doctor at once | `offerDoctor` immediately. `EmergencyFlow.kt:229` |
| F6 | Old emergency's tracker writes into a new emergency (flips countdown to tracking) | `start()` doesn't cancel old jobs. `EmergencyFlow.kt:81-93` |
| F7 | Countdown number frozen for seconds at start, not real time | Countdown starts after network + spoken intro. `EmergencyFlow.kt:93-101` |
| F8 | Previous emergency's landmark prefilled and sent | Never cleared. `EmergencyFlow.kt:72` |
| F9 | No landmark field when ambulance asked outright | Field only on countdown page. `EmergencyFlow.kt:94` |
| F10 | Double "Send now" (or tap + voice) can dispatch twice | No guard. `EmergencyFlow.kt:129-145` |
| F11 | "No units" page has no Close; any network error shown as "No ambulance available" | `EmergencyScreen.kt:84-94`, `EmergencyFlow.kt:140` |
| F12 | Vehicle number shown twice; "min"/"s" and raw status English | `EmergencyScreen.kt:197,224,233,249` |

## G. Clinic call

| ID | What the user sees | Root cause |
|---|---|---|
| G1 | Leaving the screen doesn't hang up the real call; no summary | No BackHandler/onDispose. `ClinicCallScreen.kt:117-155` |
| G2 | "End call" can speak the summary twice and log twice | Poll loop not cancelled; button has no busy state. `ClinicCallScreen.kt:136-144,211` |
| G3 | Practice mode says "confirmed" when the clinic never said yes | `LocalClinicCall.kt:50-62` |
| G4 | Offers to confirm a demo/past appointment ("Dr. Anil Mehta") and can place a real call about it | Falls back to `DEMO_APPOINTMENT`. `ClinicCallScreen.kt:65-82` |
| G5 | Real call fails silently, or shows raw English error | `ClinicCallScreen.kt:125-131` |
| G6 | Practice call blank while first turn loads; timer not reset; status shown twice | `ClinicCallScreen.kt:90,105-114,148,169,191` |

## H. Camera

| ID | What the user sees | Root cause |
|---|---|---|
| H1 | Close camera during the welcome line → mic and Live audio start anyway with no screen | Untracked startup job on app scope; `stop()` can't cancel it. `CameraSession.kt:53-60,192` |
| H2 | Product description keeps playing after Close | `sayLocal` never cancelled. `CameraSession.kt:113,164` |
| H3 | Camera permission denied → black screen for 3 minutes | No permission check; bind failure swallowed. `CameraScreen.kt:89` |
| H4 | "Connection lost" with no retry; mic keeps streaming | `CameraSession.kt:122` |
| H5 | Mute toggle does nothing when offline / mic denied | `CameraScreen.kt:109` |
| H6 | "Order online" clips the task's first line | `onBack()` → `session.stop()` → global `speaker.stop()`. `CameraScreen.kt:104` |

## I. Settings, onboarding, digest, alerts, log

| ID | What the user sees | Root cause |
|---|---|---|
| I1 | Save name / Remove / Add family / Save times can crash when backend unreachable | `pushConfig()` throws, not caught. `SettingsScreen.kt:101-142` |
| I2 | New family member / alarm time sent stale | `settings.current` lags DataStore `edit()`. `WelfareManager.kt:54,69` |
| I3 | Save buttons give no feedback; one shared sticky "Saved." shown in the wrong card | `SettingsScreen.kt:54,101,151,169,178` |
| I4 | "Test welfare alert" repeat taps spam family; Remove contact has no confirm | No busy/confirm. `SettingsScreen.kt:113,145` |
| I5 | Permission permanently denied → "Allow" does nothing | No settings fallback. `OnboardingScreen.kt:70,106` |
| I6 | SMS/calls row shows ✓ while 4 of 7 permissions missing | `Permissions.kt:59` |
| I7 | Onboarding: no language highlighted on first run; intro keeps playing in the old language after switching | Default `"auto"` not offered; `LaunchedEffect(Unit)`. `OnboardingScreen.kt:71-81` |
| I8 | Battery/exact-alarm links can crash on some phones | No try/catch. `OnboardingScreen.kt:107,110` |
| I9 | Alerts: "Scam Shield is on" even when SMS/call permission skipped | Static text. `AlertsScreen.kt:25-35` |
| I10 | Digest: "Reading…" disabled with no Stop; summary card duplicates the items and never goes away; "0 to pay" pill; "Call the clinic" under Reminders even with no appointment | `DigestScreen.kt:45-75` |
| I11 | Activity log entries English only in Hindi mode | `AgentLoop.kt:173-389`, `ActivityLogScreen.kt:34` |

## J. Language / copy consistency

Hard-coded English in an otherwise tri-lingual app: Settings labels (health line, "Chat ID", "Backend URL"), raw `e.type` badges (Alerts, Digest), TalkBack "Back"/"Camera view", notification channels and titles, clinic bubble labels, emergency units. Masculine Hindi in one line (`AgentLoop.kt:332`) vs feminine everywhere else.

---

# Fix plan

**Phase 1 — one source of truth for assistant UI** (A1–A11, B1–B3, C1, C4, C11, D2–D3)
1. `AssistantUiState` flow (Idle, Connecting, Listening, Speaking, Thinking, Working(task), Asking(q), Confirming(summary), Paused, Error(msg)) combined from assistant/live/speaker/speech/care.
2. Overlay controller *renders* that state; no direct `showCaption`/`hideCaption` from flows. Each run owns a scope whose `finally` resets to Idle, guarded by a run id so a late cleanup can't touch a newer run.
3. In-app = Compose bottom sheet; overlays only when another app is in front.
4. Mic bubble states per section B (muted mic when idle). Tap meaning follows state: Idle → talk, Working → stop task (not end Live), Live → end.
5. Starting a task/guide while Live is open pauses Live (wire up `pause()/resume()`); Live drop and task failure are spoken and shown.

**Phase 2 — safety-critical flows** (F1–F12, G1–G2, H1–H2)
Emergency: phase guards on siren/dispatch, call108 cancels countdown, BackHandler + dispose, honest cancel result, reset per start. Clinic call and camera: tracked jobs, hang up / stop on leave.

**Phase 3 — feedback, loading, dead buttons** (D1, D4–D6, D8–D11, E1–E9, C2–C3, C5–C9, G3–G6, H3–H6, I1–I11)
Plain `nav.navigate`, per-phase loaders, busy/disabled states, no duplicate hints, flows collected not read once, speaker mutex + proper stop.

**Phase 4 — visual revamp** (D7)
Design tokens shared by Compose and overlay views; coloured header band; Home as hero header + 2-column tinted tile grid; restyled sub-screens and overlay cards.

**Phase 5 — copy sweep** (B4–B5, J)
