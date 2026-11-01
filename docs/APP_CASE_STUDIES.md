# How Saathi operates other apps: Uber and Pronto

Saathi books cabs and house help for people who find phone apps hard to use. It does not have private access to
Uber or Pronto. It uses the same screen that a person sees, read through Android's accessibility service. When
that reading is not enough, it also uses a screenshot. This document goes through two real cases:

- **Uber**: the app describes its screen well through accessibility, so Saathi works from text.
- **Pronto**: a React Native app whose accessibility tree misses parts of the screen, so Saathi adds screenshots
  and taps by coordinates.

Every claim points to a file in the repo. Where something is not built, the document says so.

---

## Part 0: The one-slide version

| Step | What happens | Who decides | Where |
|---|---|---|---|
| Hear | "Saket ke liye cab book kar do" becomes intent `book_cab` with destination "Saket" | Backend `/intent`, or rules on the phone if the backend takes longer than 4 s | `backend/app/routers/agent.py`, `android/.../core/voice/LocalIntentRules.kt` |
| Launch | Saathi's own code builds the Uber deep link and opens it | Code, never the model | `android/.../features/tasks/TaskSpec.kt`, `TaskRunner.kt` |
| Look | The screen becomes a numbered list of elements, plus a screenshot when needed | Code | `android/.../core/agent/LiveScreenReader.kt`, `TreeSerializer.kt` |
| Think | The model picks **one** action per step | Model, or a compiled routine | `backend/app/llm/prompts/agent_step.md`, `backend/app/skills/engine.py` |
| Check | Every action is validated in code before it runs | Code | `android/.../core/agent/SafetyPolicy.kt`, `backend/app/schemas.py` |
| Ask | Book, pay, send, add to cart: a spoken or tapped **yes** first | The user | `AgentLoop.confirmThen`, `android/.../core/overlay/OverlayAgentUi.kt` |
| Act | Tap, type or scroll through accessibility or a gesture | Code | `android/.../core/agent/AccessibilityActionExecutor.kt` |

(`android/...` is short for `android/app/src/main/java/com/saathi/app`.)

---

## Case study 1: Uber, an app that describes its screen

### 1.1 How Saathi reads the screen

Saathi's `AgentService` is an Android accessibility service. Its config
(`android/app/src/main/res/xml/accessibility_service_config.xml`) asks to read window content and interactive
windows, report view ids, perform gestures and take screenshots.

`LiveScreenReader.read()` walks the node tree of the app's main window, plus any dialogs from other apps. It
skips Saathi's own windows and the launcher or edge-panel windows behind the app. For each visible node it
records:

| Field | Source | Notes |
|---|---|---|
| `cls` | class name, e.g. `Button`, `EditText` | |
| `text` | node text | **left out for password fields** |
| `desc` | content description | Pronto labels its buttons this way |
| `hint` | hint text (Android 8+) | e.g. "Where to?" |
| `viewId` | resource id | recorded, but **not** sent to the model by the serializer |
| `clickable`, `editable`, `scrollable`, `checkable`/`checked`, `focused`, `password` | node flags | |
| `bounds` | `[left, top, right, bottom]` in screen pixels | |
| `depth` | nesting depth | used for indentation and for trimming |

The node model is `ScreenNode` in `android/.../core/agent/ScreenModel.kt`. The reader keeps a node only if it has
bounds and is interactive or carries text or a description.

`TreeSerializer.serialize()` (`android/.../core/agent/TreeSerializer.kt`) turns this into the compact text the model
reads. Here is the ride list from the replay fixture `fixtures/screens/uber_book/04.json`, roughly as it is
serialized:

```
package=com.ubercab title="Choose a ride" secure=false
  [1] ImageButton desc="Back" clickable bounds=(20,100,140,220)
  [2] TextView "Uber Go  ₹184  4 min away" clickable bounds=(40,1300,1040,1420)
  [3] TextView "Premier  ₹236  6 min away" clickable bounds=(40,1450,1040,1570)
  [4] TextView "Auto  ₹96  3 min away" clickable bounds=(40,1600,1040,1720)
  [5] TextView "Cash" bounds=(40,1760,1040,1880)
  [6] Button "Confirm Uber Go" clickable bounds=(40,1900,1040,2020)
```

The serializer applies some limits:

- **At most 150 nodes** (`MAX_NODES`). When a screen has more, the deepest nodes that cannot be tapped go first.
- **Text is cut at 80 characters**, and newlines and quotes are cleaned up.
- **Node IDs are just positions** in this list. They are assigned again for every snapshot, so a model can never
  carry an ID over from an earlier screen.

Note that the files in `fixtures/screens/` were written by hand to match the real apps. They were not recorded
from Uber itself (NOTES.md, "Gaps").

**Screen text is data, never instructions.** The safety section of the prompt
(`backend/app/llm/prompts/_safety.md`, rule 1) tells the model to ignore on-screen text such as "ignore previous
instructions" or "call this number". The prompt is not the only defence. Even if the model obeyed such text, code
blocks the dangerous results: only deep links that Saathi built can be opened, `tel:` and `sms:` links are always
refused, apps outside the allowlist are refused, and dial codes are never typed (§1.5). NOTES.md records one
live probe in which prompt-injection text on a fixture screen was ignored.

### 1.2 How a task starts

1. **Intent and slots.** The cab task is `Tasks.UBER` in `android/.../features/tasks/TaskSpec.kt`. Its one required
   slot is `destination`. If that slot is empty, `Assistant.runTask` asks "Where do you want to go?" in the user's
   language. A `vehicle` slot (auto, bike or XL) changes the goal. Otherwise the goal is "Book the cheapest Uber
   car (not a bike or auto)", because the cheapest option is usually a Moto bike, which someone asking for a cab
   does not want (NOTES.md).
2. **Cleaning the place.** `DeepLinks.cleanDestination` strips pickup words such as "yahan se" or "from here".
3. **Finding the place.** `AndroidPlaceFinder` (`features/tasks/PlaceFinder.kt`) geocodes the name with Android's
   `Geocoder`. It searches only within ±0.3° (about 33 km) of the phone's last known position and gives up after
   3 s.
4. **Building the deep link.** `DeepLinks.uberString` builds
   `uber://?action=setPickup&pickup=my_location&dropoff[latitude]=…&dropoff[longitude]=…&dropoff[nickname]=…`.
   If the place has no coordinates, there is no link and Saathi just opens Uber.
5. **Allowlist.** `TaskRunner.run` puts that one link into `trustedDeeplinks`, and the launch passes through the same
   `SafetyPolicy.validate` check as any model action. ACT mode may only work in apps on
   `Packages.ACT_ALLOWLIST` (`android/.../util/Packages.kt`): Saathi, Uber, Amazon, Flipkart, Practo, Pronto,
   Google Calendar and Settings.
6. **Choosing between places.** For cabs, `TaskRunner` deliberately skips its own place-choice questions
   (`resolvePlace` / `PlaceChoice`) and goes straight to the deep link. If Uber shows several matches, the prompt
   tells the model to `ask_user` with at most three options, each read with its key facts. The task notes also
   tell the agent to check that the drop-off on the ride list matches the goal.

### 1.3 The agent loop

`AgentLoop.loop()` (`android/.../core/agent/AgentLoop.kt`) repeats **observe → pre-check → decide → validate →
act**, for at most 25 steps.

**What the model sees each step.** The phone sends `AgentStepRequest`: the goal (with the task's landmark notes
and the user's profile line), the mode, the reply language, the serialized screen, a screenshot if one was taken,
the **last 6 actions** with their outcomes, the task hint and a run id. The backend
(`backend/app/llm/gemini_client.py`, `agent_step`) adds the system prompt `agent_step.md` along with its shared
judgement, safety and language parts. History lines carry the user's answers, for example
`-> user answered: 60 minutes`, so the model does not ask the same question twice.

**What the model may return.** It returns exactly one JSON action, checked against `backend/app/schemas.py` and
`android/.../core/net/Models.kt`:

| Action | Fields | Notes |
|---|---|---|
| `tap` | `id` | preferred over coordinates |
| `tap_xy` | `x`, `y` | only when a screenshot was attached (Case study 2) |
| `type` | `id`, `text` | only into an editable node |
| `scroll` | `direction` up/down/left/right, optional `id` of the list | |
| `back`, `home` | | |
| `open_app` | `package` | allowlist only |
| `open_deeplink` | `uri` | only links Saathi built |
| `wait` | `ms` (0–10 000 on the server; the phone clamps it to 100–5 000) | |
| `ask_user` | `question` | spoken; the user can answer by voice or type |
| `confirm` | `summary`, `then` | `then` can only be `tap` or `tap_xy` |
| `highlight` | `id?`, `instruction`, `gesture?` | GUIDE only |
| `done`, `fail` | `message` | spoken to the user |

**ACT and GUIDE modes.**

- **ACT** taps on the user's behalf, inside allowlisted apps only.
- **GUIDE** never taps. It works in any app, because all it does is draw a ring, speak an instruction and wait
  for the user to act. Three checks enforce this:
  - the phone's `SafetyPolicy.GUIDE_ACTIONS` allows highlight, ask_user, done, fail and wait;
  - the backend's `GUIDE_ACTIONS` allows highlight, ask_user, done and fail, and rejects anything else twice, once
    in `gemini_client.agent_step` and again in `routers/agent.py::_checked`;
  - `highlight` itself is blocked in ACT mode.

### 1.4 Where Saathi stops for approval

`SafetyPolicy.CONFIRM_WORDS` matches **book, booking, confirm, pay, place order, buy now, proceed to pay, send,
request, schedule, add to cart**, plus Hindi equivalents (बुक, पुष्टि, भुगतान, ऑर्डर, भेजें, कार्ट में). It
checks a node's text, description and hint together.

There are two ways to reach a yes/no question:

1. **The model asks first.** This is the expected path. For the Uber fixture, the scripted step
   (`fixtures/agent_scripts/uber_book.json`) is:
   ```json
   {"action":"confirm",
    "summary":"Uber Go to Apollo Hospital, Sarita Vihar. Fare ₹184, the car is 4 minutes away. Book it?",
    "then":{"action":"tap","id":6}}
   ```
   The prompt asks for a summary that includes the price. The Uber task notes ask the model to read the ride type,
   and the prompt's example also includes how far away the car is. The summary is written by the model; code
   only checks that it is not empty.
2. **The model taps directly.** Then `validate` returns `NeedsConfirm`, and the loop asks
   *"Shall I press "Confirm Uber Go"?"* itself. This is covered by
   `SafetyPolicyTest.directTapOnCommitButtonNeedsConfirm` and
   `AgentLoopReplayTest.directTapOnBookButtonIsTurnedIntoAConfirm`.

**The confirm sheet** (`OverlayAgentUi.confirm`) speaks the summary, then "please say yes or no", and listens. A
tap on the sheet's buttons or a spoken answer settles it, whichever comes first. If the answer is heard but
unclear twice, it counts as **no**. After a no, Saathi says so and the run ends as `Cancelled`, before the button
is pressed (`AgentLoopReplayTest.sayingNoToTheConfirmSheetStopsBeforeTheButton`). After a yes, the inner tap is
**validated again** with `confirmed = true` and only then performed.

### 1.5 The code checks that keep the AI in line

None of these depend on the model behaving well.

| Check | What it does | Where |
|---|---|---|
| Secret screens | FLAG_SECURE windows, PIN / UPI PIN / MPIN / CVV / CVC / OTP fields: the agent says "please enter it yourself" and waits for "done". This applies in GUIDE mode too | `SafetyPolicy.isSecretScreen`, `preCheck` |
| Login screens | An editable password field stops ACT mode until the user logs in | `isLoginScreen` |
| Typing | Never into a password, PIN or OTP field. Never dial codes (`*#…`). Only into editable nodes | `validate` → `TYPE` |
| Foreign apps | ACT mode on a screen outside the allowlist: Saathi reopens the task's app (at most twice), then stops | `preCheck` → `FOREIGN_APP`, `MAX_REOPEN` |
| Settings | Only look-and-sound pages. Pages and rows about security, accounts, apps, location, Wi-Fi, payments, reset and similar stop the agent or are blocked | `isRestrictedSettingsPage`, `isRestrictedSettingsRow` |
| Links | `open_deeplink` only for URIs in `trustedDeeplinks`. `tel:` and `sms:` are always refused | `validate` → `OPEN_DEEPLINK` |
| Confirm shape | A confirm needs a summary and an inner action. No nested confirms. The inner action is checked too | `validate` → `CONFIRM`; `schemas.ConfirmTarget` |
| Schema | The backend parses every action, whether from the model or a routine, into a strict union (`parse_action` / `dump_action`). Anything invalid becomes a kind `fail` | `backend/app/schemas.py`, `routers/agent.py::_checked` |
| Bad output | An invalid model answer is retried once with the error attached, the second time on the route's `second_opinion` model. The phone ends the run after 2 incomplete actions in a row | `gemini_client.agent_step`; `AgentLoop` `invalidInARow` |
| Loops | The same screen fingerprint more than 3 times: "I'm stuck… shall I show you instead?" | `MAX_SAME_SCREEN`, `TreeSerializer.fingerprint` |
| Step limit | 25 steps per run | `MAX_STEPS` |
| Harder model | When the last two history steps were `back` or `fail`, the step goes to the `agent_step_hard` route | `gemini_client.agent_step`, `backend/app/models.yaml` |
| Slow model | The fallback model starts after `hedge_s` (5 s for agent steps), and the first good answer wins. The phone retries once quietly and says "one moment" after 6 s | `gemini_client._generate`; `AgentLoop.withSlowCue` |
| Blocked actions | A blocked action is logged and fed back to the model as `BLOCKED by safety policy: …`. It is never performed | `AgentLoop.blocked` |

**What leaves the phone, and what is logged.**

- Password-field text is never read (`LiveScreenReader.walk`).
- Secret screens stop the agent **before** any model call. `preCheck` runs before `nextAction`, so a PIN screen is
  never sent to the server.
- The phone's activity log replaces typed text with "…" (`AgentLoop.logLine`).
- The backend logs only sizes, the action type, the handler and latency, never screen text (`routers/agent.py`).
  Its memory checkpoint leaves out screen text and the `text` and `message` fields.
- **One honest gap:** agent-step screen text and screenshots are **not** passed through `Redactor` on the phone.
  That redaction runs only on classification, appointment extraction and digest calls
  (`android/.../core/net/BackendClient.kt`). The backend also stores each step's screen text in `skill_run_steps`
  so it can learn routines. `docs/PRIVACY_AUDIT.md` lists 7 days of retention for screen-task step history.

### 1.6 Worked example: "book a cab to Saket"

The fixture screens use Apollo Hospital. The steps below are the same for Saket, but no fares are given for
Saket because none were measured.

1. **Voice → intent.** The user speaks. `/intent` (or `LocalIntentRules` if the backend is late) returns `book_cab`
   with destination "Saket". *Decided by: the intent model or the phone's rules.*
2. **Slots.** The destination is filled, so there is no question. *Decided by: `Assistant.runTask`.*
3. **Place and link.** Geocoder finds Saket near the phone, and `DeepLinks.uber` builds the link. *Code.*
4. **Launch.** The `open_deeplink` is validated against `trustedDeeplinks` and performed. Saathi waits 1.5 s,
   waits for the screen to settle, and waits up to 5 s for a real first screen (`TaskRunner.awaitLoaded`). *Code.*
5. **Ride list.** Uber opens on the ride list, because the deep link set the drop-off. The screen is read,
   pre-checked and serialized. *Code.*
6. **Choose a ride.** `/agent/step` asks the compiled routine if one is active, otherwise the model. The task notes
   say to ignore Moto, Bike and Auto rows and pick the cheapest car. *Model (or routine).*
7. **Confirm.** The model returns `confirm` with a summary of car, fare and distance. If it taps "Confirm Uber Go"
   directly, `SafetyPolicy` turns the tap into a question. *Code enforces; the user decides.*
8. **Yes.** The sheet speaks the summary and hears "haan". The tap is validated again and performed. *User, then
   code.*
9. **Done.** Once a "…is arriving" line is visible, the model returns `done` and reads the driver, car and ETA,
   e.g. *"Your cab is booked. Ramesh is coming in a white Swift Dzire, 4 minutes away."* (fixture). *Model.*
10. **Report.** The phone posts the outcome to `/agent/outcome`. This never blocks the user and gives up after 5 s.
    The run can now teach a routine. *Code.*

On a real Uber run on a Samsung phone, the slow step was the one just before the confirm. One model took 16 s,
returned invalid JSON, took 12 s more on the retry, and then timed out at 20 s on the next step. That run led to
the current timeouts, fallback and hedging (NOTES.md, "Silent speech on Samsung, slow Uber confirm";
`models.yaml` comments).

### 1.7 Learning: Uber runs become a routine

Successful ACT runs of one task (`task_hint` = `uber_book`) are compiled into a **routine**: declarative JSON steps,
not code (`backend/app/skills/routine.py`, `compiler.py`, `engine.py`; `docs/LEARNING.md`,
`docs/COMPILED_CODE.md`).

- **Matching.** Each step lists strings it `expect`s on screen and finds its target **by text, hint, desc or regex**
  on today's screen. Stored node IDs are never used.
- **Lifecycle.**
  - A routine becomes a candidate after 3 successful runs.
  - It is tested by replaying runs, including at least 90% of held-out runs.
  - After family approval (or `SKILL_AUTO_APPROVE`) it runs in **shadow**: the model still acts and the routine's
    choices are compared.
  - After 3 fully agreeing shadow runs it becomes **active**.
  - A miss in 2 of the last 5 runs **degrades** it.
  - 1 in 20 active steps is also checked against the model in the background (canary).
- **Still under SafetyPolicy.** `routine.validate()` rejects:
  - a `tap` on a commit-word target that is not inside `confirm`;
  - any `open_app`, `open_deeplink`, `tap_xy`, `home` or `highlight`;
  - dial codes;
  - typing into PIN, password or OTP hints.

  Routine actions then pass the same `_checked` schema on the backend and the same `SafetyPolicy` on the phone,
  so the yes/no question for "Confirm Uber Go" is asked exactly as before.
- **Measured offline only.** `docs/SIMULATION.md` (mock backend, 2 rides a week for 30 days) found 38% fewer
  model steps for cab rides once the routine is active. No live measurement exists.

---

## Case study 2: Pronto, an app that hides parts of its screen

### 2.1 What is different about Pronto

Pronto (`com.company.pronto`) is built with Expo / React Native (NOTES.md). Its tree is not empty. Buttons appear
as clickable `ViewGroup`s labelled by content description, and the fixtures
(`fixtures/screens/house_help/*.json`) copy that shape. But the tree **misses things that matter**. When "Add" is
tapped it becomes "Added" and a green "Go to cart" bar appears, and neither change reaches accessibility
(`LiveScreenReader.kt` comments, `TaskSpec.kt` house-help notes). Pronto's accessibility scroll action is also often
accepted without the list moving (`AccessibilityActionExecutor.scroll`).

There is also no usable deep link. `pronto://service/<id>` needs server-side listing ids, so the task opens the app
and the agent taps through (NOTES.md, "Deep links for Uber and Pronto").

### 2.2 When Saathi switches to screenshots

`LiveScreenReader.read()` attaches a screenshot when **any** of these is true:

| Trigger | Rule |
|---|---|
| Sparse tree | fewer than 3 interactive nodes. The serializer also adds `needs_screenshot=true` to the header |
| Nothing changed | the screen's fingerprint equals the previous read's, a sign that the last action did something the app did not report |
| Known visual app | the package is in `VISUAL_APPS`, which today is only Pronto, so every Pronto step gets a screenshot |

If the tree is empty **and** no screenshot could be taken (FLAG_SECURE windows fail to capture), the snapshot is
marked `secure`. The agent then treats it as a secret screen: it asks the user to do this part and say "done".

### 2.3 How the screenshot is taken and sent

- **Android 11+ only.** On older phones no screenshot is taken, so the model works from the tree alone.
- **Saathi's overlays are kept out of the picture** (commit `d56fa6e`, "Pronto: screenshots without Saathi's
  overlays…"):
  - On **Android 14+**, when the app has a single window, only that window is captured
    (`takeScreenshotOfWindow`).
  - Otherwise, for example when Pronto's slot picker opens as a second window, the full screen is captured. The
    caption, mic bubble and question card are hidden first (`OverlayController.setHiddenForCapture`), there is a
    120 ms pause for one frame, and they come back afterwards.
- **Size.** The image is scaled to at most 1080 px tall and saved as JPEG quality 70. Capture gives up after 2 s.
- **Coordinate note.** The phone writes a note above the element list, for example *"Screenshot attached: W×H image
  of the app's screen… tap_xy takes SCREEN pixels (image x and y times 2.00, then add X to x and Y to y)."* The scale
  factor and the window offset come from code. **The model does the multiplication.**
- **Sending.** The backend puts the JPEG in front of the prompt as an image part (`gemini_client.agent_step`). Both
  agent routes are marked `vision: true` in `models.yaml`.

### 2.4 How a vision tap works

The prompt (`agent_step.md`) says: use `tap_xy` only when a screenshot is attached. When one is attached, trust it
over the element list, and never repeat a tap whose effect the screenshot already shows. The Pronto task notes are
specific: tap a card's "Add" **once**, then check the screenshot and tap "Go to cart" with `tap_xy`. Tapping "Add"
again removes the service.

What code does with `tap_xy`:

1. **Schema.** `x` and `y` must be integers (`schemas.TapXY`, `ActionParser.isComplete`).
2. **Screenshot required.** `SafetyPolicy.validate` blocks `tap_xy` unless this snapshot has a screenshot or is
   marked `needsScreenshot` (`tapXyOnlyWithScreenshotAndStillGated`).
3. **Commit check by position.** `ScreenSnapshot.nodeAt(x, y)` finds the deepest node whose bounds contain the
   point. If that node's text, description or hint is a commit word, the tap becomes a yes/no question, exactly as
   with `tap`.
4. **Settings rows.** The same restricted-row check applies to the node under the point.
5. **Performing it.** `AccessibilityActionExecutor.gestureTap` sends a 60 ms gesture at that point.

**What is not built:**

- Code does **not** clamp the coordinates to the screen and does not check them against bounds. A point off the
  screen simply hits nothing.
- The model does not report a label for a `tap_xy`. Code only knows what the accessibility node under the point
  says.

### 2.5 Waiting, scrolling and answers

- **Splash screens.** `TaskRunner.awaitLoaded` reads the screen every 500 ms, for up to 5 s, until the app shows at
  least 3 interactive nodes and does not need a screenshot. This avoids spending a model step on Pronto's logo.
- **Scrolling.** For React Native lists, Saathi first swipes inside the named or largest matching list and uses the
  accessibility scroll action only as a fallback. Date and slot rows scroll sideways.
- **Settling.** After each action Saathi waits for 350 ms with no screen events, up to 2 s, because Pronto's
  banners never stop animating.
- **Questions.** The task notes have the agent ask about duration and price ("30 mins ₹40, 60 mins ₹79 or 90 mins
  ₹119?"). The user's answer goes back as `-> user answered: …` in the history (commit `d56fa6e`). Dates are
  passed as "tomorrow (Sun 1 Nov)", because Pronto's chips say "Today" and "Tomorrow" (`DayWords`).
- **Stopping before payment.** The goal is to *add the visit to the cart*. "Add to cart" is wrapped in a confirm,
  then the agent finishes with `done` and tells the user to pay in Pronto themselves. The prompt's safety rules
  forbid checkout and payment, and any "Proceed to pay" or "Pay" tap would also hit the confirm gate.

### 2.6 Which safety checks still hold without good node text

| Check | Still holds for Pronto? |
|---|---|
| Allowlist, foreign-app stop, trusted links only, never `tel:` / `sms:` | Yes: these are based on the package and the link, not on node text |
| Confirm gate on `tap` | Yes, where Pronto labels the button by content description (e.g. "Add to cart", "Proceed to pay ₹40" in the fixtures) |
| Confirm gate on `tap_xy` | **Only if a node with a commit word lies under the point.** If the button is missing from the tree, code cannot tell it is a commit button. Then only the prompt's "wrap it in confirm" rule applies |
| PIN / OTP / password screens | Detected from editable nodes and labels. A secure window that cannot be captured counts as secret. A PIN pad drawn **only** in pixels with no labelled nodes would **not** be detected by code |
| Typing | `type` still needs an editable node id. There is no way to type by coordinates |
| Uncertainty | The model is told to `ask_user` or `fail` rather than guess. The phone ends the run after the same screen more than 3 times, 2 invalid actions in a row, or 25 steps |
| Routines | **Cannot learn the vision part.** `tap_xy` is forbidden in routines, so a Pronto routine could only cover steps reachable by text |

### 2.7 Uber versus Pronto

| | Uber (accessibility) | Pronto (accessibility + screenshot) |
|---|---|---|
| What Saathi knows | Text, descriptions, hints, flags and bounds of every element | Labelled buttons from the tree, plus whatever the model sees in the picture ("Added", the "Go to cart" bar) |
| How it taps | By node ID: accessibility click, with a gesture at the node's centre as fallback | By node ID where possible, `tap_xy` gesture where the tree is blind |
| Launch | Deep link with the destination already set | Opens the app; the agent taps through from home |
| Data per step | Text only (up to 150 nodes) | Text plus one JPEG (≤ 1080 px tall, quality 70). Byte size per step has not been measured |
| Latency | One model call per step. `models.yaml` notes 2.7–6 s per call measured for the primary model | The same call with an image. No separate measurement exists |
| Reliability | High where labels are good. Node IDs cannot drift | Depends on the model reading the picture and scaling coordinates itself. No coordinate bounds check |
| Commit-button guarantee | Enforced in code on every tap | Enforced in code only where a labelled node is under the point; otherwise it relies on the prompt |
| Learning | Can become a compiled routine | Only the text-reachable steps; `tap_xy` is never compiled |
| Payment | Saathi never pays; the user's yes is needed to book | Stops at the cart; the user pays in Pronto |

---

## What is not built / known limits

- **No live recordings of the real apps.** `fixtures/screens/uber_book` and `house_help` are hand-written. Real-app
  flows were tried on a phone (NOTES.md mentions a Galaxy S21 FE), but there is no automated end-to-end test on
  live Uber or Pronto. Pronto's checkout screen has not been seen yet.
- **No coordinate validation** for `tap_xy` beyond requiring a screenshot, and no model-reported label for the
  point.
- **Pixel-only secret screens** (a PIN pad with no accessible nodes) are not detected by code.
- **Agent-step screen text and screenshots are not redacted** on the phone before sending, and step screen text is
  kept on the server for learning (7 days, `docs/PRIVACY_AUDIT.md`).
- **The confirm summary is written by the model.** Code checks only that it exists, not that the fare in it matches
  the screen. An on-device OCR price check (`PriceGuard`, commit `1b96ea7`) was added and later **removed** (commit
  `6086c3b`). It is not in the current code.
- **Place choice is skipped for cabs.** `TaskRunner` bypasses `resolvePlace` for Uber, so choosing between places
  happens inside Uber through the model's `ask_user`.
- **Screenshots need Android 11+**, and window-only capture needs Android 14+. Older phones briefly hide Saathi's
  overlays instead.
- **The routine savings figure (38%) comes from an offline simulation**, not from live use.
- **Small mismatches between docs and code:** `docs/LEARNING.md` says routine targets can match by viewId, but
  `routine.Target` has no viewId field. The serializer also never sends view ids to the model.
