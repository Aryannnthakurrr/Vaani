# Saathi (Vaani) — UI brief for Google Stitch

Paste **Part 1** first (it sets the design system), then paste each screen in **Part 3** as its own prompt, one at a time. Part 2 is the map of how screens connect. Part 4 covers the floating overlays that appear over *other* apps.

---

## Part 1 — Product and design system (paste first)

**What the app is.** Saathi is an Android voice assistant for elderly, non‑technical people in India. The user mostly *talks* to it. It books doctors, calls cabs and house help, reads out messages, warns about scams, checks products with the camera, calls ambulances, and teaches the user how to use other apps by pointing at buttons on screen. Family members get alerts on Telegram.

**Who it is for.** Older adults (60+), low tech confidence, often poor eyesight, often one‑handed. Hindi, English and Hinglish (Hindi written in English letters) are all first‑class; every label exists in all three, so layouts must survive long Devanagari text.

**Design direction: minimal, calm, obvious.**
- Fewer things on screen. One main action per screen. No decoration for its own sake: no gradients, no glassmorphism, no illustrations, no emoji as icons, no animated backgrounds, no coloured card blocks.
- Neutral warm‑grey page (`#F6F6F3`), white surfaces, near‑black text (`#14181C`), one accent: deep teal `#0E6B5A` (white text on it). Red `#C42121` only for danger/emergency/stop. Green `#177245` only for "done / yes / safe". Amber `#A35A06` only for caution.
- Control edges must be clearly visible: 1.5dp outline `#8A9096` on white cards and buttons (≥3:1 contrast). Body text ≥ 4.5:1.
- Large type, always: body 22sp, titles 24–28sp, page headings 32sp, bold/semibold. Font: Mukta (covers Latin + Devanagari). Never below 16sp.
- Touch targets ≥ 56dp, primary buttons 72dp tall, spacing ≥ 12dp. Corner radius 16–20dp everywhere (one radius, not many).
- Icons: one set, outlined, 24–28dp, always paired with a text label. Never icon‑only except Back and Settings (which have 52dp round white buttons with an outline).
- Every screen has a plain header: small muted caption on top, big title below, Back button (round, outlined) at left. No coloured header band.
- Light theme only (high contrast, familiar). Respect system font scale.
- Motion: tiny. Press feedback only. Content fades in.

**Reusable components**
1. **Big button** — full width, 64–72dp, filled teal (primary) / filled green (yes) / filled red (danger) / soft grey (secondary).
2. **Tile** — white card, 92dp tall, outlined; a 44dp rounded‑square icon in pale teal on the left, label (20sp, up to 2 lines) on the right; optional count badge in its top‑right corner (teal circle, white number).
3. **Info card** — white, outlined, title (24sp) + optional body (22sp, muted), optional small status pill (e.g. "DANGER" red, "CAUTION" amber, "INFO" green) and an optional leading icon.
4. **Status pill** — small rounded label with tinted background.
5. **Toggle row** — label left, switch right, 72dp tall.
6. **Big text field** — 64dp, floating label, large text.
7. **Voice wave** — a row of 5–7 soft bars that move with the voice, with one hint line under it ("Speak freely, I'm listening").
8. **Chat bubble** — Saathi left (white, outlined), user right (pale teal).
9. **Success view** — large green check in a pale‑green circle, one heading, one sentence, an optional small note.
10. **Empty note** — one muted sentence, centred.

---

## Part 2 — Screen map

```
Onboarding (first run, and "Run setup again")
   └─> Home
Home ── Settings ─── Run setup again ─> Onboarding
     ├─ Warnings banner ───────────────> Safety alerts
     ├─ Doctor tile ───────────────────> Doctor (care)  ──> Emergency (if urgent)
     ├─ Cab tile            (runs a task, shows overlay cards; stays on Home)
     ├─ House help tile     (runs a task, shows overlay cards; stays on Home)
     ├─ Updates tile ──────────────────> My updates ──> Call the clinic
     ├─ Check product tile ────────────> Camera
     ├─ Teach me tile       (guide mode: highlights buttons in other apps)
     ├─ History link ──────────────────> What Saathi did
     └─ Talk to Saathi (bottom bar) ───> Talking view (replaces tiles in place)
Floating mic bubble (over every other app) ── tap: talk / stop ── long‑press: open Saathi
Full‑screen scam alert (over everything, when a scam is detected)
```

There is **no tab bar and no side menu** by design. Home is the only hub; every other screen has a Back button.

---

## Part 3 — Screens (one Stitch prompt each)

### 3.1 Home
Plain page, no header band. Top: muted date line ("Sunday, 1 November") with a 52dp round outlined **Settings** (gear) button at the right edge; below it a 32sp bold greeting that changes by time of day and uses the user's name ("Good morning, Ramesh ji" / "सुप्रभात, रमेश जी" / "Good evening…").

If there were suspicious things in the last 24h, a full‑width pale‑red banner (not a tile) with a red shield icon, red text "5 warnings today" and a chevron; tapping opens Safety alerts. Hidden when there are none.

Then a **2‑column grid of six tiles**, three rows, all visible without scrolling:
1. Doctor (medical‑bag icon)
2. Cab (taxi icon)
3. House help (cleaning icon)
4. Updates (mail icon) with a count badge of useful messages this week
5. Check product (camera icon)
6. Teach me (question‑mark icon)

Under the grid, a quiet text link "History" (no box).

**Fixed bottom bar** (above the system nav bar, hairline divider on top): one big teal button, 72dp, mic icon + "Talk to Saathi". It is the single most important control on the screen.

**State: Talking** (when a conversation is open) — the tiles are replaced, in the same space, by the *Talking view*:
- one large calm teal circle in the middle that slowly breathes when idle, swells when the user speaks, and pulses when Saathi speaks; faint rings ripple outward;
- under it, Saathi's last sentence in 28sp bold, centred, and the user's last words in 22sp muted quotes beneath it.
- The bottom bar becomes two stacked buttons:
  - **Mute / Unmute** (64dp, soft grey with mic icon; when muted it turns solid near‑black with a crossed‑mic icon) — stops Saathi hearing the user without ending the conversation. While muted, a line above the buttons says "Mic is muted. Saathi cannot hear you."
  - **End conversation** (72dp, pale red fill, red text, stop icon).
- While Saathi is working on a task (booking a cab, etc.) the bottom bar shows a line "Working on it. Press Stop anytime." and the red **Stop** button.
- Cab and House help tiles are dimmed and disabled while a task runs.

**State: Connecting** — status line "Connecting…" above the button.

### 3.2 Settings
Header: back button + "Settings". Single scrolling column of white section cards, each with a small heading:
1. **Language** — four big choice buttons in a 2×2 grid: Auto, English, हिन्दी, Hinglish (selected one is filled teal). Helper line: "Auto replies in the same language you speak."
2. **Your name** — big text field + "Save name" button, inline success/failure note.
3. **Family (Telegram)** — an info card "How family joins" (family member opens the Saathi bot in Telegram, sends /start, gets a chat ID); list of added family members (name + chat ID + red "Remove", with a confirm dialog "Remove Anita? They will stop getting your alerts on Telegram." [Remove] [Keep]); two fields (name, Telegram chat ID) and "Add family member".
4. **Daily check‑in** — two time fields ("Ask me at" HH:MM, "Alert family at" HH:MM), "Save times", and "Test welfare alert".
5. **Alerts** — toggle row "Tell family about scam alerts".
6. **Apps Saathi can use** — list of apps (Uber, Ola, etc.) each with "installed / not installed".
7. **Connection** — status card (green = reachable, red = not: "Server not reachable. Saathi is using the phone's own voice and checks."; shows Live AI vs Practice mode, voice engine, Telegram on/log only), "Server address" field + "Save address", toggle "Work offline (no backend)", button "Run setup again".
8. **Demo profile** (only in demo builds) — buttons to switch between sample users (name, age, conditions) and "Back to my own profile".
9. **Developer** (hidden section at the bottom) — Record screen, Show a test scam alert, data‑used counter with Refresh/Reset.

### 3.3 Onboarding / Setup
Header "Welcome to Saathi". Saathi speaks the intro aloud. Top: **Language** 2×2 choice buttons. Below: "Allow what you are comfortable with — you can skip any of these." Then a vertical list of **permission rows**, each: icon, bold title, one plain sentence of *why*, and an "Allow" button that turns into a green "Allowed" state:
Microphone · Notifications · Messages and calls (scam shield) · Camera and calendar · Your area (location) · Let Saathi help in other apps (accessibility) · Read my notifications · Show the Saathi button (overlay) · Keep Saathi running (battery) · Exact reminders (alarms).
A hint card appears when Android won't ask again ("Tap Allow to open App info, then Permissions, and turn it on there") and one for "Switch is grey? Open App info → ⋮ → Allow restricted settings".
Bottom: big teal "Start using Saathi".

### 3.4 Safety alerts
Header "Safety alerts". Top status card: **Scam Shield is on** (green accent: "I check every message and warn you during suspicious calls. OTPs never leave your phone.") or **off** (red accent, explanation, big "Turn on Scam Shield" button; special wording if Android won't ask again). Below, a list of alert cards, newest first, each with: a level pill (**DANGER** red / **CAUTION** amber / **INFO** green), a type icon + title (Suspicious call / Suspicious message / Dangerous link / Safety alert), one‑sentence reason in plain words, and a relative time ("2 hours ago"). Empty state: "No alerts. Saathi is watching your messages and calls for scams."

### 3.5 My updates (digest)
Header "My updates". Top: big button "Read aloud" (toggles to "Stop reading"). Summary pills: "2 payments", "1 warnings", "9 in total". Section **This week**: cards for each message, categorised with a pill and icon — Bill (due date, amount, merchant), Renewal, Appointment, Delivery, Paid, Possible scam (red, "Do not reply or share any code"), Message. Section **Reminders**: amber‑accent cards with title, body, and the reminder time; a button "Call the clinic for me". Empty states: "Nothing new. Bills and appointments will show up here." / "No reminders yet."

### 3.6 Call the clinic
Header with the clinic name; subtitle "Saathi can call the clinic for you" / "Dialling…" / live timer "Practice call · 0:42" / "Call ended".
Pre‑call: an info card for the next appointment ("Saathi will confirm your appointment and ask how long the wait is") or "No upcoming appointment"; big buttons: **Call the clinic now**, **Practice call (type the clinic's side)**, **Not feeling well? Find care**.
During call: a live transcript list (alternating bubbles: Saathi speaking… / Waiting for the clinic / the clinic's words), a text field "Clinic says…" with Send (practice mode only), and a red **End call** button (confirm dialog: "End the call? Going back will hang up the call to the clinic." [End call] [Stay on the call]).
After: a green **Call summary** card and a big "Done" button. Error cards: line busy, no answer, call failed, phone calls not set up.

### 3.7 Doctor (care)
Header "Doctor". Under it, small muted disclaimer: "I am an AI assistant, not a doctor. In an emergency, call 108."
The screen is a **conversation**: chat bubbles (last six) of Saathi's questions and the user's answers. The intake is a live voice call — a **voice wave** with the hint "Speak freely, I'm listening" and, directly beneath it, a secondary button **"Mute my microphone"** (turns into "Mic is off. Tap to unmute"). For yes/no questions: the hint changes to "Say yes or no, or tap" and two buttons appear, green **Yes** and grey **No**; free text questions offer a text field "Or type here" with Send.
Progress states (a single centred line with a small spinner): "Getting ready…", "Thinking…", "Finding hospitals near you…".
Then **Note card**: "See a cardiology doctor" or red "Could be an emergency", with "AI‑generated. Not a diagnosis." and Show/Hide note.
Then **hospital cards** being called: name, distance/travel time, status pill (Calling… amber / Available green / No answer grey / No doctor grey).
Then the **Recommendation card**: pill "Best option", doctor + hospital name, time slot, distance, fee (₹), voice wave "Say yes or no, or tap", green **Book**, grey **Other times** (expands more slots), and a quiet "No thanks".
While booking: "Calling the hospital to book…". If the hospital must be called manually, a **Call to book: +91…** button.
Failure/empty states: message + up to five tappable hospital phone buttons.
Success: the Success view "Booked" with doctor, time, hospital, and "Added to your calendar".
Always at the bottom: red text link "Emergency? Call 108". "Start again" button when idle.

### 3.8 Emergency (ambulance)
A **full‑screen red page, white text** (the only red page in the app).
1. Countdown: "SENDING AN AMBULANCE IN" + a huge number inside a circular progress ring (110sp), a line "Your location and symptoms will be shared with the ambulance crew", a location chip (area name), a voice wave "Say 'I'm OK' or 'send now'", an optional text field for address/landmark, a huge white pill **"✕ Cancel, I'm OK"** (largest target on the screen), and below it two text links "Send now" and "Call 108". There is **no mute** here by design.
2. Tracking: a map showing the user and the ambulance moving, a big ETA ("6 min", 54sp) and distance ("2.3 km away"), a status headline ("Ambulance on the way" / assigned / arrived / patient on board / going to hospital / at the hospital / completed), ambulance type (ALS/BLS), facts row (driver, paramedic nurse with names), "Your family has been alerted", "Call 108" and "Close". A small "Simulation: no real ambulance is sent" label in demo mode.
3. Cancelled: white page with success view "Cancelled — No ambulance is coming. If you feel worse, call 108." and buttons "Call 108", "Close".
4. Failure: red page, "No ambulance available. Call 108 now." (or location not found / could not request) with a big white **Call 108** button.

### 3.9 Camera (check a product)
Full‑screen camera preview. At the bottom a floating dark panel with large buttons. States: needs permission ("Saathi needs the camera to read the pack" + **Allow camera**), camera failed, offline‑voice notice, mic unavailable notice. While live: a **Mute / Unmute** mic button, **Retry**, and a red **Close**; the user can talk to Saathi about what is in view. When Saathi has read the pack, a **product card** slides up: product name, a status pill (EXPIRED red / Expires soon amber / Fresh green / Expiry not read grey), "Expiry: 2026‑03‑01", and two buttons **Read again** and **Order online**.

### 3.10 What Saathi did (activity log)
Header "What Saathi did". Muted line: "Everything Saathi did for you, in order. Family can check this too." List of cards, newest first: plain‑language sentence as title, app name + relative time as body, status pill: Finished (green) / Stopped (amber) / Didn't work (amber) / Blocked for safety (red). Empty: "Nothing yet."

### 3.11 Talk‑to‑Saathi sheet (in‑app prompts)
When Saathi needs an answer while its own app is open, a bottom sheet slides up with a voice wave and:
- **Question**: hint "Speak, or type below", text field "Your answer", buttons **OK** and **Cancel**.
- **Confirmation**: summary sentence ("Uber Go to AIIMS, ₹240"), hint "Say yes or no, or tap", green **Yes**, grey **No**.
- **Caption with a button**: a sentence and one big green button (e.g. "Done").

---

## Part 4 — Overlays (drawn over *other* apps, not inside Saathi)

These are small, quiet cards in the same teal / white / red language. They sit over Uber, WhatsApp, a bank app, etc.

1. **Floating mic bubble** — a 56–64dp draggable circle at the screen edge, always available. Five states: *idle* (white disc, grey mic with a red slash — "not listening"), *connecting* (teal disc, mic, ring orbiting), *listening* (teal disc, soft ring that grows with the user's voice), *speaking* (teal disc, animated speech bars), *working* (teal disc, white stop square, ring orbiting). Tap = start / end talking, or stop the task. Long‑press = open Saathi. Announced to screen readers in all three languages.
2. **Caption bar** — big readable text at the top of the screen showing whatever Saathi is saying; shrinks to a slim bar while a task runs; optionally has one action button ("Done" / "Your turn").
3. **Stop chip** — small white pill with red text "Stop" in a corner while a task is running.
4. **Confirm card** (bottom sheet) — "It always asks first": one short summary, green **Yes**, soft grey **No** (saying no is never dangerous, so it is not red), a moving voice hint.
5. **Question card** — text field "Or type your answer here" plus OK / Cancel; Saathi listens too.
6. **Guide highlight** ("Teach me") — a pulsing teal ring drawn around the real button the user should tap in another app, or an animated swipe arrow for "scroll down", with one instruction line and "Waiting for you" / "Done".
7. **Result toast** — for ~4 seconds: large green check + one sentence ("Uber Go booked, arriving in 4 min"), or an amber "!" with the reason.
8. **Full‑screen scam alert** — red takeover, shown on a suspicious call, SMS or link: a big warning title ("Scam warning"), one plain sentence of why, and big buttons: **Hang up** (only during a call), **I'm safe, continue** (needs a second tap: "Tap again to confirm"), plus a note that family has been told. The loud siren and spoken warning play with it.

---

## Part 5 — Copy and tone rules for generated text
- Short plain sentences, no jargon, no "AI/algorithm/server" words in user‑facing text.
- Address the user with respect: "Ramesh ji".
- Buttons are verbs: "Book", "Call the clinic now", "Turn on Scam Shield".
- Always show English labels in the mockups, but leave room for ~40% longer Hindi/Hinglish text.
