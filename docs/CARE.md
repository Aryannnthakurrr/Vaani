# Symptom-to-care

The user describes symptoms by voice. Saathi writes a clinical intake note that answers one question: *could this be
a medical emergency?* **Yes or unsure → Path A** (siren, countdown, automatic mock ambulance dispatch, live
tracking). **No → Path B** (find hospitals, call three in parallel, recommend one, book it by phone, add it to the
calendar). Built from the team's workflow spec; deviations are listed at the end. Not a diagnosis: every screen says so.

## Where the code lives

| Piece | Files |
|---|---|
| Intake, red-flag safety net, note, routing | `backend/app/care/triage.py`, `safety_net.py`, prompt `llm/prompts/triage.md` |
| Hospital search and ranking | `care/places.py` (Places API New, Routes API), `care/ranking.py` |
| Parallel inquiry calls, booking call | `care/calls.py` (Bolna), `care/extract.py`, `care/orchestrator.py`, `scripts/bolna_agent.py` |
| Dispatch and tracking | `care/dispatch.py` → `mock_ambulance/` (separate service, port 9000) |
| API | `backend/app/routers/care.py` |
| Phone | `android/.../features/care/` (`CareFlow`, `CareScreen`, `EmergencyFlow`, `EmergencyScreen`, `Siren`), map page `assets/ambulance_map.html` |

Entry points: the voice intent `care` (any symptom, "not feeling well", "ambulance"; it wins over `book_doctor` and
`guide`), the Live voice tool `open_screen("care")`, and "Not feeling well? Find care" on the clinic-call screen.

## Intake (workflow §3)

1. The phone sends its last known position, accuracy and reverse-geocoded address with `POST /care/session`.
2. Each spoken turn goes to `POST /care/{id}/intake` and comes back as the next question or the finished note.
   At most three follow-ups, red flags first, one at a time.
3. `safety_net.scan` / `scan_answer` run on every turn, in English, Hindi and Hinglish, with simple negations
   removed. Any hit stops the questions and forces `emergency.answer = YES`, never downgraded.
4. The route: safety net hit, YES or UNCERTAIN → A; only NO → B. The note always contains the EMERGENCY ASSESSMENT
   block.
5. Without a model (mock mode, timeout): scripted red-flag questions, then "Is this an emergency?", then a
   template note. The user's own "yes" becomes UNCERTAIN, so they get the cancellable countdown.

## Path A: emergency (workflow §4)

- The phone shows the full-screen red countdown (`EMERGENCY_COUNTDOWN_SEC`, default 30). The siren sweeps
  650 ↔ 1500 Hz on the alarm stream, so it sounds in silent mode, and the phone vibrates with the screen kept on.
  Buttons: **I'M OK, CANCEL** (the largest), **SEND AMBULANCE NOW** and **Call 108**, which opens the dialler and
  never dials by itself. A landmark box appears when the location is more than 100 m off or unknown.
- At zero, or on "Send now": `POST /care/{id}/emergency/dispatch`. The backend builds the request from the note:
  priority P1–P4 from acuity, ALS for P1/P2 cardiac, stroke, breathing, trauma or unconscious cases, AVPU, ATMIST,
  and the full note. It posts the request to the mock and then polls it every second.
- Tracking: `dispatch_status` (with the route points once) and `ambulance_location` events. The phone's map is a
  bundled Leaflet page in a WebView. The marker turns with the heading and glides between updates, the travelled
  part of the route is grey, and the ETA counts down every second.
- "I'm OK" before dispatch stops everything; after dispatch it cancels with `PATIENT_OK`. Both then ask "Shall I find
  you a doctor instead?" and a yes goes to Path B.
- Fallbacks: 503 `NO_UNITS_AVAILABLE` or an unreachable mock → "Call 108 now" plus a Telegram alert to the family
  contacts (the existing welfare channel).
- Crew view: `GET {MOCK_AMBULANCE_BASE_URL}/driver/{dispatch_id}?key=…` shows ATMIST, priority, address, landmark,
  the route map and the full note. The URL comes in the `dispatch_created` event.

### Mock ambulance API (`mock_ambulance/`)

`bash scripts/mock_ambulance.sh` runs it on :9000. It uses the `X-Api-Key` header.
`POST /v1/dispatch/requests` → 201 `{dispatch_id, status, estimated_assignment_sec, tracking}`;
`GET /v1/dispatch/{id}`; `POST /v1/dispatch/{id}/cancel` (409 after `PATIENT_ON_BOARD`);
`WS /v1/dispatch/{id}/track`; optional `callback.webhook_url`. Errors: 400, 401, 404, 409, and
503 `NO_UNITS_AVAILABLE` (`MOCK_AMBULANCE_NO_UNITS=1` or the header `X-Debug-No-Units: 1`).
The simulation starts the ambulance 2–5 km away, routes it with `computeRoutes`, or along a synthetic street-grid
path without a Maps key, and moves it every second at 25–45 km/h × `SIM_SPEED_MULTIPLIER`. After `ARRIVED_AT_SCENE` it picks the patient
up and drives to a stand-in hospital 3–6 km away: `PATIENT_ON_BOARD`, `EN_ROUTE_TO_HOSPITAL`, `ARRIVED_AT_HOSPITAL`,
`COMPLETED`. The backend relays every status and the new route, and the phone announces each step.

## Path B: find, call, book (workflow §5)

1. `POST /care/{id}/search` runs in the background. Text Search for the note's queries plus Nearby Search
   (hospitals), merged by place id. The radius widens 8 → 15 → 25 km until five candidates are left.
2. Filter: no phone, closed now (unless open 24 h), wrong type (dental, eye, lab unless the specialty matches).
   ETAs come from one traffic-aware route matrix. Capability (0–1) comes from one batched model call, or a
   name-based guess. Score = 0.45·capability + 0.35·(1 − eta/max) + 0.20·log reviews·rating/5. The top three are
   called and the next two kept as backups.
3. Three Bolna inquiry calls run at once (`asyncio.gather`). No answer, voicemail, a timeout or a number that isn't
   allowed → that call's slot moves on to a backup.
4. Each finished transcript is turned into a `CallResult` (a model with a strict schema, or the rule reader: slots
   such as "kal 11 baje", doctor name, fee).
5. Re-rank: specialty confirmed, then the earliest slot, ETA, fee and rating. The recommendation is spoken and
   shown with **Yes, book · Pick another slot · No thanks**, and a spoken yes/no works as well.
6. `POST /care/{id}/book` places the Bolna booking call. Results: `confirmed` (with a booking reference and calendar
   fields), `slot_unavailable` (alternatives, which the user approves before another call), or `failed`. Two failures
   → "call to book" with the hospital's number.
7. Calendar: the phone writes the event itself with `CalendarWriter.insertEvent`, with alerts 1 day and 2 hours
   before. The final card has **Navigate** (Google Maps directions) and **Share** (`wa.me`).

Every call goes through Bolna agents created with `scripts/bolna_agent.py --mode inquiry` / `--mode booking`. The
agents open by saying they are an AI assistant, share only what each call needs, never book on the inquiry call and
never accept a different time without the user. **Only numbers in `CALL_ALLOWED_NUMBERS` are ever dialled.**

**Masking.** `MASK_HOSPITAL_NUMBERS=1` (the default) makes every hospital call ring a test number: the first in
`CALL_ALLOWED_NUMBERS`, or a teammate's from `DEMO_PHONE_MAP`. The screen keeps each hospital's real name and number.
When several hospitals share one test phone, they are called one after another (a phone takes one call at a time)
and backups are skipped.

## API and events

All routes need `X-Saathi-Device`; a session belongs to the device that created it.

| Route | Purpose |
|---|---|
| `POST /care/session` `{location?, patient, reply_style}` | → `{session_id}` |
| `POST /care/{id}/location` | Update location or landmark |
| `POST /care/{id}/intake` `{text}` | → `{done:false, question}` or `{done:true, route, note, safety_flags}` |
| `GET /care/{id}/events?after=N` | Events after sequence N (the phone polls every 1–1.5 s) |
| `POST /care/{id}/search` | Path B, in the background |
| `POST /care/{id}/book` `{hospital_id, slot, patient?}` | Booking call, in the background |
| `POST /care/{id}/emergency/alert` | → `{countdown_sec}` |
| `POST /care/{id}/emergency/dispatch` `{trigger: countdown\|send_now, landmark?}` | Create the dispatch |
| `POST /care/{id}/emergency/cancel` | "I'm OK" |

Events: `session_started`, `intake_question`, `intake_note`, `care_status`, `hospitals_found`, `call_status`,
`call_transcript`, `call_result`, `recommendation`, `no_options`, `care_fallback`, `booking_status`,
`emergency_alert`, `dispatch_created`, `dispatch_status`, `ambulance_location`, `dispatch_failed`,
`emergency_cancelled`, `error`. The server logs only event types, never symptoms or transcripts.

## Setup

1. `.env`: `GOOGLE_MAPS_API_KEY` (Places API New + Routes API), `BOLNA_API_KEY`, `BOLNA_INQUIRY_AGENT_ID`,
   `BOLNA_BOOKING_AGENT_ID`, `CALL_ALLOWED_NUMBERS` (the test phone first). `MASK_HOSPITAL_NUMBERS=1` keeps every
   call on the test phone; `DEMO_PHONE_MAP` spreads them over teammates' phones.
2. `backend/.venv/Scripts/python scripts/bolna_agent.py --mode inquiry`, then `--mode booking`; paste the printed ids.
3. `bash scripts/mock_ambulance.sh` (port 9000) next to `bash scripts/backend_dev.sh`.
4. Without a Maps key and with `DEMO_MODE=1`, three clearly labelled "(demo)" hospitals that fit the specialty stand in
   for search results.

## Deviations from the workflow spec

- **Calls use Bolna**, not Twilio ConversationRelay (the team's provider; Twilio has been removed). Answers are extracted from the transcript
  when each call ends; there is no live `record_answer` tool. Live transcript lines still stream to the screen, and
  IVR keypresses (`press_digits`) are not supported.
- **Polling, not WebSockets**, between the phone and the backend, and between the backend and the mock, at 1 s. The
  mock still offers its WebSocket and webhook.
- **The calendar entry is written on the phone**, which already has calendar permission, so there is no Google OAuth
  and no `.ics` file.
- **The map is Leaflet with OpenStreetMap tiles in a WebView**, so it needs no Maps SDK key.
- **"Wake lock"** is the screen kept on while the emergency screen is open; the siren uses the alarm stream.
- **The phone gets the crew-view link with the mock's dev key in it.** That's acceptable for a mock only; a real
  provider must never be wired that way.
