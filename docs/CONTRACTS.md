# Saathi shared contracts

Single source of truth for everything the Android app and the backend must agree on.
If you change something here, change both sides.

## Auth

Every request except `GET /health` carries `X-Saathi-Device: <device uuid>`. Missing → `401`.
The camera WebSocket passes it as a query parameter: `/live/camera?device=<uuid>`.

## Reply style

`reply_style` is always one of `en | hi | hinglish`.

## Agent action schema

One flat JSON object with an `action` discriminator. Every model response includes `reason` (logged, never spoken).

| action | fields |
|---|---|
| `tap` | `id` |
| `tap_xy` | `x`, `y` (only when a screenshot was attached) |
| `type` | `id`, `text` |
| `scroll` | `direction` = up/down/left/right |
| `back`, `home` | – |
| `open_app` | `package` |
| `open_deeplink` | `uri` (the app only honours URIs built by its own task code) |
| `wait` | `ms` |
| `ask_user` | `question` |
| `confirm` | `summary`, `then` (a nested action, normally `tap`) |
| `highlight` | `id` (optional), `instruction`, `gesture` (optional: `swipe_up`/`swipe_down`) |
| `done` | `message` |
| `fail` | `message` |

## Screen fixtures — `fixtures/screens/<scenario>/<NN>.json`

```json
{
  "package": "com.android.settings",
  "title": "Settings",
  "secure": false,
  "nodes": [
    {"cls": "TextView", "text": "Display", "desc": null, "hint": null, "viewId": "android:id/title",
     "bounds": [40, 820, 1040, 940], "clickable": true, "editable": false, "scrollable": false,
     "checkable": false, "checked": false, "focused": false, "depth": 2}
  ]
}
```

Node IDs are assigned by the serializer in list order starting at 1 (the order in the file is tree pre-order).
Serialized line format (one per node, two spaces of indent per depth):

```
[14] Button "Where to?" clickable bounds=(40,820,1040,940)
[15] EditText hint="Search" editable focused
```

Screen header lines precede the nodes:

```
package=com.ubercab title="Uber" secure=false
```

`ReplayScreenReader` (debug) walks a scenario folder in file order, advancing one screen after every executed
`tap`, `tap_xy`, `type`, `scroll`, `back` or `open_*` action (and after a confirmed `confirm`).

## Mock agent scripts — `fixtures/agent_scripts/<scenario>.json`

```json
{
  "scenario": "settings_font_size",
  "mode": "GUIDE",
  "goal_keywords": ["font", "text size", "akshar"],
  "steps": [
    {"when_contains": ["Display"], "action": {"action": "highlight", "id": 5, "instruction": "Tap Display", "reason": "..."}}
  ]
}
```

Mock selection: script = `task_hint` if it names a scenario, else the first script whose `goal_keywords` match the goal.
Step = the first step at index ≥ `len(history)` whose `when_contains` strings all appear in `screen_text`;
otherwise `steps[min(len(history), last)]`. No script → `fail`.

## Backend API shapes

- `POST /voice/stt` multipart: `file` (WAV 16 kHz mono), `language_hint` (`en-IN|hi-IN|unknown`), `keyterms` (comma-separated)
  → `{transcript, language_code, reply_style}`. `503` when Sarvam is unavailable.
- `POST /voice/tts` `{text, reply_style, voice?}` → `audio/wav` bytes. `503` when unavailable.
- `POST /intent` `{utterance, reply_style?}` →
  `{intent, slots: {destination?, product?, specialty?, doctor?, area?, time?, date?, service?, quantity?}, reply_style, reply_text}`.
  `intent` ∈ `book_cab | book_house_help | shop_online | book_doctor | guide | camera | digest | settings | chitchat | unknown`.
- `POST /agent/step` `{goal, mode: ACT|GUIDE, language, screen_text, package, screenshot_b64?, history: [{action, reason}], task_hint?}` → action.
- `POST /classify/messages` `{messages: [{id, sender, text}]}` → `{results: [{id, category, amount?, due_date?, merchant?, summary}]}`.
  `category` ∈ `bill_due | subscription_renewal | appointment | delivery | payment_done | personal | promo | scam_suspected | other`.
  `amount` is a number in rupees, `due_date` is `YYYY-MM-DD`.
- `POST /extract/appointment` `{screen_text}` → `{doctor, clinic, address, phone, start_iso, fee}`.
- `POST /digest` `{items: [{category, merchant?, amount?, due_date?, summary}], reply_style, user_name?, today?}` → `{text}`.
- `POST /links/check` `{url}` → `{url, final_url, verdict: safe|suspicious|dangerous, reason_simple, reason_simple_hi, signals: [str], cached}`.
- `WS /live/camera?device=…` client → `{"type":"config","reply_style":…}`, `{"type":"frame","jpeg_b64":…}`,
  `{"type":"audio","pcm_b64":…}` (16 kHz s16le mono), `{"type":"end"}`.
  server → `{"type":"product_card","card":{name, mrp, quantity, expiry_iso, expiry_status: ok|soon|expired|unknown, confidence}}`,
  `{"type":"text","text":…}`, `{"type":"audio","pcm_b64":…,"sample_rate":24000}`, `{"type":"error","message":…}`.
- `POST /welfare/heartbeat` `{device_id, ts}` (ts = epoch millis) → `{ok}`.
- `POST /welfare/config` `{device_id, user_name, contacts: [{name, telegram_chat_id}], check_time}` → `{ok}`.
- `POST /welfare/test` `{device_id}` → `{text, sent: [chat_id], logged: true}`.
- `POST /scam/notify_family` `{device_id, summary}` → `{sent: [chat_id], logged: true}`.
- `GET /health` → `{status, mock, sarvam, telegram, models}`.

## Safety phrase table — `android/app/src/main/assets/phrases.json`

`{ "<key>": {"en": "…", "hi": "…", "hinglish": "…"} }`. `scripts/render_alert_clips.py` renders each entry to
`android/app/src/main/res/raw/<key>_<style>.wav`. The app plays the clip if it exists, otherwise speaks the text
with on-device TTS. Keys are lowercase `[a-z0-9_]`.
