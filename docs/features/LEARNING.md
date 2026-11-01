# Saathi learns: compiled skills, voice, Meko memory

Updated 2026-11-01 to match the code. Saathi's backend uses AI models to **learn**, and to listen and talk; small
repeated parts move to tested code. The first time it does something (an Uber booking, sorting a bank SMS), a model
solves it step by step. Once the same kind of task has succeeded a few times, a strong model **compiles a skill** from
those successful runs. The skill is tested on runs it never saw, approved by family, and then used instead of the
model, so repeated work costs no tokens and is faster. When an app changes its screens, the skill fails its checks,
the model takes over again, and a new version is compiled from the fresh runs. Conversations, calls and decisions
are never handed to code: the model still understands the request and words every reply. The design is ported from
the `sorted` project, with the gaps found there fixed: a real Docker sandbox, plan-level shadow testing for
side-effecting routines, rolling health checks, and a code hash verified before every run.

The elder talks to Saathi by **voice**. Family members use Telegram and the family dashboard to check in and approve
new skills; the elder never sees Telegram.

Related documents:
- [COMPILED_CODE.md](COMPILED_CODE.md): everything compiled, the sandbox and every safety layer.
- [PERSONAL_ABILITIES.md](PERSONAL_ABILITIES.md): the three demo users and their abilities.
- [MEKO_STATE.md](MEKO_STATE.md): memory, who learnt what, appointments.
- [HEALTHCARE_KT.md](HEALTHCARE_KT.md): the doctor and emergency features.
- [APP_CASE_STUDIES.md](APP_CASE_STUDIES.md): how Saathi operates Uber (accessibility) and Pronto (vision).

## Models (`backend/app/models.yaml` is the only place IDs live)

| Job | Model | Why |
|---|---|---|
| Intent, agent steps, link verdicts, classify, extract, digest, care triage/ranking/extraction, ability replies | **Gemma 4 31B** on DigitalOcean serverless inference (`app/llm/do_inference.py`); `second_opinion` **Qwen 3.8 Max** when Gemma's answer is invalid; `fallback` Gemini 3.5 Flash Lite (Google) on an outage or timeout | Measured 2.7–6 s per call with JSON mode and images; agent steps also hedge after 5 s (a second model is started if the first stalls) |
| Skill compiler, `agent_step_hard` (repeated failures on a screen) | **Qwen 3.8 Max** (high thinking for compiles) → fallback Gemini 3.5 Flash Lite | Strongest reasoning on the same endpoint, 1–2 s per step |
| Shopping camera (live) | `gemini-3.8-live` (voice Sulafat) | Realtime video frames and audio in, audio out |
| Realtime voice conversation (`voice_live`) | `gemini-3.8-live` → fallback `gemini-3.1-flash-live-preview` | Built, but **switched off in the app** (see §2) |
| Turn-based TTS (every spoken reply, confirm sheets, digest) | Sarvam **Bulbul v4-flash**, voice Aparna, pace 1.1 → fallback `gemini-3.8-flash-lite-tts` | Bulbul 0.7–1.3 s vs Gemini TTS 2.4–3.9 s per sentence (measured); the same voice as the hospital-call agent. Hinglish is converted to Devanagari before speaking so Bulbul pronounces it right |
| Turn-based STT | Sarvam **Saaras v4**, codemix mode → fallback `gemini-3.5-transcribe` | Codemix keeps the script the Hindi/Hinglish style detector needs |
| Hospital phone calls | Bolna voice agents (`scripts/bolna_agent.py`): OpenAI `gpt-5.4-mini` for the conversation, Sarvam Saaras and Bulbul for listening and speaking | See HEALTHCARE_KT.md |

Sarvam is also the voice of the **pre-rendered safety clips** that ship in the APK (no network at alert time). With no
Sarvam key, every speech path runs on Google alone.

## 1. Skills

The complete reference for compiled code (all three kinds, every safety layer, the lifecycle, personal abilities) is
[COMPILED_CODE.md](COMPILED_CODE.md); this section is the summary.

### Kinds

| Kind | Learns from | Compiled into | Runs where |
|---|---|---|---|
| `routine` | Successful ACT runs of one task (`task_hint`, e.g. `uber_book`) | A declarative **routine**: JSON steps (below). No code, so it is easy to check, diff and audit | Backend `/agent/step` answers from the routine instead of calling the model |
| `recogniser` (built: `intent`) | Every LLM answer to `/intent` (utterance → intent + slots), kept 30 days | Python `recognise(text) -> {"intent","slots"} \| None`; None = "not sure, ask the LLM". The spoken reply always comes from fixed templates | Docker sandbox on the server (`app/skills/recogniser.py`) |
| `parser` (not built) | LLM classifications of SMS from one sender + category | A Python `parse(text) -> dict` | Docker sandbox |
| personal **ability** (one user) | That user's own repeated bookkeeping (sugar readings, BP, expenses) | Python `run({text, state, today})`, tested at every backend start | Sandbox; the model words the reply from its result (COMPILED_CODE.md §5) |

Code skills are accepted only with ≥ 95% precision on the held-out samples they answer and ≥ 30% coverage. Shadow:
the skill runs beside the LLM on live requests; one disagreement degrades it, 20 agreements activate it
(`SKILL_CODE_SHADOW_AGREE`). Compiles need `SKILL_MIN_SAMPLES` (40) samples; the job runs every 30 min.

### Routine format (compiler output, validated with Pydantic)

```json
{
  "task": "uber_book",
  "slots": ["destination"],
  "steps": [
    {"expect": ["Where to?"], "action": "tap", "target": {"text": "Where to?"}},
    {"expect": ["Pickup location"], "action": "type", "target": {"hint": "Where to?"}, "text": "{destination}"},
    {"expect": ["{destination}"], "action": "tap", "target": {"text_contains": "{destination}", "index": 0}},
    {"expect": ["Confirm"], "action": "confirm", "summary": "{ride} to {destination}, {fare}. Book it?",
     "read": {"ride": {"text_regex": "^(Uber \\w+)"}, "fare": {"text_regex": "₹\\s?\\d+"}},
     "then": {"action": "tap", "target": {"text_regex": "^Confirm"}}},
    {"expect": ["arriving"], "action": "done", "message": "Your cab is booked. {driver_line}",
     "read": {"driver_line": {"text_contains": "arriving"}}}
  ]
}
```

- **Matching:** the step whose `expect` strings all appear on the current screen and which comes after the last
  executed step. Targets resolve to the current snapshot's node IDs by text, hint, desc or regex, never by
  stored IDs.
- **Safety is unchanged:** routines go through the same Android `SafetyPolicy`. Commit buttons must be wrapped in
  `confirm` (checked again by the backend validator), and routines can never contain `open_deeplink`, dial codes or
  PIN entry.
- **No match, a missing target, or an unexpected screen:** the backend calls the model for that step, marks the routine
  run as a miss, and the run's remaining steps go to the LLM.

### Lifecycle (`skills` table)

`candidate → tested → (family approves) → shadow → active → degraded → candidate v+1`. A rejected version is
`retired`, and so are older versions once a newer one is active.

| Transition | Rule |
|---|---|
| → candidate | ≥ `SKILL_MIN_RUNS` (3) successful LLM runs in the group, or more new runs since the last compile |
| candidate → tested | Compiler output passes schema and static checks, **every visible run replays**, and **≥ 90% of held-out runs replay** (30% held out, minimum 1). Up to 2 attempts, with the failures fed back |
| tested → shadow | Family approval (Telegram button, dashboard, or `SKILL_AUTO_APPROVE=1` for demos) |
| shadow → active | `SKILL_SHADOW_AGREE` (3) shadow runs where the routine's proposed action matched the model's on every step. Only the model's actions are returned in shadow, so nothing is executed twice |
| active → degraded | A routine miss in 2 of the last 5 runs (rolling window), or a parser output that fails validation. The LLM takes over immediately |
| canary | 1 in 20 active runs also asks the model (in the background) and compares, so a silently wrong skill is caught |

### Sandbox (server)

Python skills run in the `saathi-skill-sandbox` image (`python:3.12-slim`, unprivileged user):

```
docker run --rm -i --network none --read-only --tmpfs /tmp:size=4m --memory 128m --cpus 0.5 \
  --pids-limit 32 --cap-drop ALL --security-opt no-new-privileges --user 65534:65534 saathi-skill-sandbox
```

- The input is JSON on stdin (code plus cases). Each case runs in a fresh interpreter. Wall time is capped at 10 s.
- A static check runs first (`app/skills/sandbox.py`): imports only from `re, json, datetime, math, string,
  unicodedata, difflib, collections, itertools`; no dunders; no `open/exec/eval/compile/getattr/type/__import__`;
  no lambdas, globals or async; a 20 kB size cap.
- Each skill's sha256 is stored and verified before every run.
- `SKILL_SANDBOX=docker` on the server, `subprocess` on a laptop (same runner and static check, no container
  limits), `off` to never run code. Serving waits at most 3 s for the sandbox, then the LLM answers.

### Cost

`GET /skills` lists every skill version with its status, and reports steps served by routines vs the model and the
tokens spent compiling. Not built yet: USD estimates and the "handled N steps on its own" line on the family page.

## 2. Voice

### What runs today: turn-based

The realtime WebSocket conversation caused problems on phones, so it is **switched off** (`LIVE_VOICE = false` in
`android/.../core/net/BackendClient.kt`, commit "websockets removed"). The mic bubble, "Talk to Saathi" and the doctor
intake all use the turn-based path:

1. The phone records until the user stops speaking, then Sarvam Saaras transcribes it (`/stt`). The speech style
   (English, Hindi or Hinglish) is detected from the words.
2. Simple cab requests are routed on the phone at once (`LocalIntentRules`). Everything else goes to `POST /intent`:
   - **Personal ability:** if the user's demo profile has an active one that fits, its code does the bookkeeping and
     the model words the reply.
   - **Compiled intent recogniser:** answers when it is sure.
   - **Otherwise:** the intent model (Gemma) answers, and its answer becomes training data for the recogniser.
3. The phone acts on the intent: a task, a guide, a screen, or a spoken reply. Sarvam Bulbul speaks the reply. Yes/no
   questions and safety lines use bundled clips or on-device speech when the network is slow.

Typing works everywhere. Debug builds accept `adb shell am broadcast -a com.saathi.app.DEBUG_SAY` as typed speech.

### Built, switched off: Gemini Live — `WS /live/voice?device=…`

The code is still there and comes back with `LIVE_VOICE = true`:
- **Audio:** the app streams 16 kHz PCM up and plays 24 kHz PCM down.
- **Relay:** the backend relays to `gemini-3.8-live` with the Saathi persona, the reply style and the user's
  remembered facts.
- **Tools** (the app executes most of them):

| Tool | Args | App does |
|---|---|---|
| `start_task` | `task` (book_cab, book_house_help, shop_online, book_doctor), slots | `Assistant.startTask` |
| `start_guide` | `goal` | Guide mode |
| `open_screen` | `screen` (digest, camera, alerts, settings, clinic_call, log) | Navigates |
| `read_updates` | — | Returns digest items (fixed code) for the model to speak |
| `check_link` | `url` | `/links/check` verdict |
| `latest_otp`, `speak_private` | — | Private values are spoken by the phone with the mic off, never sent to the model |
| `remember` | `fact`, `topic`, `key` | Stored by the backend locally and in Meko, as agent `saathi-voice` |
| `forget` | `topic`, `key` | Deletes one fact, or a whole topic, locally and in Meko |

**Still live:** the shopping camera (`WS /live/camera`) does use Gemini Live: about 1 frame a second and the mic go
up, and audio and product cards come back. Its sugar and salt finding comes from fixed code (`app/abilities/food.py`)
as facts for the model's nudge.

## 3. Memory and agent state — Meko (Yugabyte)

The full write-up is [MEKO_STATE.md](MEKO_STATE.md). Meko is reached through MCP at `https://mcp.mekodata.ai/mcp`
(`Authorization: Bearer mko_tkn_…`, `MEKO_API_KEY`),
Meko's default datapack. `app/memory/` uses one interface with two backends:
`LocalMemory` (SQLite) answers every read, and `MekoMemory` (MCP) is the durable copy, filled in the background.
**Each elder has their own Meko datapack**: Meko's search covers a whole datapack, so the datapack is the boundary
between people. Inside it, `agent_id` names the part of Saathi writing (`saathi-voice`, `saathi-tasks`), and all of
them share that one elder's context. Reads stay local because a Meko search takes 15-18 s (NOTES.md).

| What | Meko tool | Used for |
|---|---|---|
| Facts and preferences about the elder ("prefers Uber Go", "Dr. Mehta is her cardiologist"), each with a topic and key, in their own language | `memory_add` / `memory_delete_by_id` in the elder's datapack | Built: the voice `remember` / `forget` tools store and delete them; the Live prompt gets all of them plus the elder's name at the start of every conversation; clinic calls get only the name (`AUDIENCES`) |
| Agent run state: per step the task, mode, screen size and app, action, reason, and which handler answered (skill or LLM) | `conversation_create` per run + `conversation_add_message` per step (`reasoning`, `plan`) | Built: a decision trail per run, written off the request path. Not built: resuming a run after an app restart |
| Who learnt what: each part of Saathi writes as its own agent (`saathi-voice`, `saathi-care`, `saathi-tasks`, `saathi-inbox`, `saathi-camera`, `saathi-abilities`, `saathi-family`), with `learned_from` in each memory's metadata | `memory_add` per agent profile conversation | Built: see [MEKO_STATE.md](MEKO_STATE.md). Booked appointments (`saathi-care`) feed "cab to my doctor's appointment" |
| Compiled abilities' state (readings, totals, stock) | `conversation_add_message` per ability (`saathi-abilities`) | Built |
| Compiled skills as files, token usage, shared learnings | `artifact_put`, `track_token_usage`, `memory_promote` | Not built yet |

Privacy: screen text is **not** sent to Meko at all, only its size and the app's package. `remember` refuses
anything with OTP/PIN/password/card/Aadhaar words or long numbers (`safe_fact`). Raw audio is never stored. A copy
of every memory is also kept locally, so a Meko outage only loses the run trail, never the conversation.
