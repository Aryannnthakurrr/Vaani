# Saathi learns: compiled skills, realtime voice, Meko memory

Saathi's backend uses Gemini to **learn**, not to run. The first time it does something (an Uber booking, sorting
a bank SMS), a model solves it step by step. Once the same kind of task has succeeded a few times, a strong model
**compiles a skill** from those successful runs. The skill is tested on runs it never saw, approved by family, and then
used instead of the model, so repeated work costs no tokens and is faster. When an app changes its screens, the skill
fails its checks, the model takes over again, and a new version is compiled from the fresh runs. The design is ported
from the `sorted` project, with the gaps found there fixed: a real Docker sandbox, plan-level shadow testing for
side-effecting routines, rolling health checks, and a code hash verified before every run.

Everything the elder hears and says goes over **voice** (Gemini Live). Family members use Telegram and the family
dashboard to check in and approve new skills. The elder never sees Telegram.

## Models (all Google; `backend/app/models.yaml` is the only place IDs live)

| Job | Model | Why |
|---|---|---|
| Skill compiler, `agent_step_hard` | `gemini-4-argon` → fallback `gemini-3.1-pro-preview` | Strongest reasoning and coding; used rarely (compiles), so cost does not matter |
| Agent steps, link verdicts | `gemini-3.5-flash` (minimal thinking) → fallback `gemini-3.5-flash-lite` | `gemini-3.8-flash` was overloaded (503s) and rejects minimal thinking when measured; revisit |
| Intent, classify, extract, digest, clinic call | `gemini-3.5-flash-lite` → fallback `gemini-3.5-flash` | Fast and cheap |
| Realtime voice conversation, camera | `gemini-3.8-live` → fallback `gemini-3.1-flash-live-preview` | Native audio in and out: ~1.5 s connect, ~0.9 s to first audio (measured) |
| Turn-based TTS (narration, confirm sheets, digest) | Sarvam Bulbul first, `gemini-3.8-flash-lite-tts` fallback | Measured: Gemini TTS 2.4–3.9 s vs Bulbul 0.7–1.3 s for one sentence, so Sarvam stays first |
| Turn-based STT (yes/no, answers) | Sarvam Saaras first, `gemini-3.5-transcribe` fallback | Gemini writes Hinglish in Devanagari; Saaras codemix keeps the script the style detector needs |

The conversation itself runs on Gemini Live, so the slower turn-based path is only used inside tasks. Sarvam is
also the voice of the **pre-rendered safety clips** that ship in the APK (no network at alert time). With no Sarvam
key, every speech path runs on Google alone.

## 1. Skills

### Kinds

| Kind | Learns from | Compiled into | Runs where |
|---|---|---|---|
| `routine` | Successful ACT runs of one task (`task_hint`, e.g. `uber_book`) | A declarative **routine**: JSON steps (below). No code, so it is easy to check, diff and audit | Backend `/agent/step` answers from the routine instead of calling Gemini |
| `recogniser` (built: `intent`) | Every LLM answer to `/intent` (utterance → intent + slots), kept 30 days | Python `recognise(text) -> {"intent","slots"} \| None`; None = "not sure, ask the LLM". The spoken reply always comes from fixed templates | Docker sandbox on the server (`app/skills/recogniser.py`) |
| `parser` (not built) | LLM classifications of SMS from one sender + category | A Python `parse(text) -> dict` | Docker sandbox |

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
  executed step. Targets resolve to the current snapshot's node IDs by text, hint, desc, regex or viewId, never by
  stored IDs.
- **Safety is unchanged:** routines go through the same Android `SafetyPolicy`. Commit buttons must be wrapped in
  `confirm` (checked again by the backend validator), and routines can never contain `open_deeplink`, dial codes or
  PIN entry.
- **No match, a missing target, or an unexpected screen:** the backend calls Gemini for that step, marks the routine
  run as a miss, and the run's remaining steps go to the LLM.

### Lifecycle (`skills` table)

`candidate → tested → (family approves) → shadow → active → degraded → candidate v+1`. A rejected version is
`retired`, and so are older versions once a newer one is active.

| Transition | Rule |
|---|---|
| → candidate | ≥ `SKILL_MIN_RUNS` (3) successful LLM runs in the group, or more new runs since the last compile |
| candidate → tested | Compiler output passes schema and static checks, **every visible run replays**, and **≥ 90% of held-out runs replay** (30% held out, minimum 1). Up to 2 attempts, with the failures fed back |
| tested → shadow | Family approval (Telegram button, dashboard, or `SKILL_AUTO_APPROVE=1` for demos) |
| shadow → active | `SKILL_SHADOW_AGREE` (3) shadow runs where the routine's proposed action matched Gemini's on every step. Only Gemini's actions are returned in shadow, so nothing is executed twice |
| active → degraded | A routine miss in 2 of the last 5 runs (rolling window), or a parser output that fails validation. The LLM takes over immediately |
| canary | 1 in 20 active runs also asks Gemini (in the background) and compares, so a silently wrong skill is caught |

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

`GET /skills` lists every skill version with its status, and reports steps served by routines vs Gemini and the
tokens spent compiling. Not built yet: USD estimates and the "handled N steps on its own" line on the family page.

## 2. Voice (Gemini Live) — `WS /live/voice?device=…`

The elder taps the mic bubble or "Talk to Saathi" and has a **natural spoken conversation**. The app streams 16 kHz
PCM up and plays 24 kHz PCM down. The backend relays to `gemini-3.8-live` with the Saathi persona, the reply style, the
user's remembered facts (from the local store), and these **tools**, which the app executes:

| Tool | Args | App does |
|---|---|---|
| `start_task` | `task` (book_cab, book_house_help, shop_online, book_doctor), slots | `Assistant.startTask` |
| `start_guide` | `goal` | Guide mode |
| `open_screen` | `screen` (digest, camera, alerts, settings, clinic_call) | Navigates |
| `read_updates` | — | Returns digest items (fixed code) for the model to speak |
| `check_link` | `url` | `/links/check` verdict |
| `remember` | `fact` | Stores locally, mirrored to Meko (server side, no app round trip) |

Client → server messages: `{"type":"config","reply_style"}`, `{"type":"audio","pcm_b64"}`, `{"type":"text","text"}`
(typed input), `{"type":"tool_result","id","result"}`, `{"type":"end"}`.
Server → client messages: `{"type":"audio","pcm_b64","sample_rate":24000}`,
`{"type":"transcript","role":"user|saathi","text","final"}`, `{"type":"tool","id","name","args"}`,
`{"type":"interrupted"}`, `{"type":"error","message"}`.

While a task runs, the Live session pauses. The task's confirm and ask steps use the turn-based path, with Gemini TTS
and transcription (or on-device speech if those are slow or offline). Typing still works everywhere.

## 3. Memory and agent state — Meko (Yugabyte)

Meko is reached through MCP at `https://mcp.mekodata.ai/mcp` (`Authorization: Bearer mko_tkn_…`, `MEKO_API_KEY`),
Meko's default datapack. `app/memory/` uses one interface with two backends:
`LocalMemory` (SQLite) answers every read, and `MekoMemory` (MCP) is the durable copy, filled in the background.
**Each elder has their own Meko datapack**: Meko's search covers a whole datapack, so the datapack is the boundary
between people. Inside it, `agent_id` names the part of Saathi writing (`saathi-voice`, `saathi-tasks`), and all of
them share that one elder's context. Reads stay local because a Meko search takes 15-18 s (NOTES.md).

| What | Meko tool | Used for |
|---|---|---|
| Facts and preferences about the elder ("prefers Uber Go", "Dr. Mehta is her cardiologist") | `memory_add` / `memory_search`, `agent_id = elder-<sha256(device)[:16]>`, one profile conversation per elder | Built: the voice `remember` tool stores them; they are recalled into the Live system prompt at the start of every conversation |
| Agent run state: per step the task, mode, screen size and app, action, reason, and which handler answered (skill or LLM) | `conversation_create` per run + `conversation_add_message` per step (`reasoning`, `plan`) | Built: a decision trail per run, written off the request path. Not built: resuming a run after an app restart |
| Compiled skills, token usage, shared learnings | `artifact_put`, `track_token_usage`, `memory_promote` | Not built yet |

Privacy: screen text is **not** sent to Meko at all, only its size and the app's package. `remember` refuses
anything with OTP/PIN/password/card/Aadhaar words or long numbers (`safe_fact`). Raw audio is never stored. A copy
of every memory is also kept locally, so a Meko outage only loses the run trail, never the conversation.
