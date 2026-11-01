# Compiled code in Saathi: how the assistant turns repeated AI work into tested code, and keeps it safe

The complete reference (2026-11-01) for everything Saathi runs as compiled code: what gets compiled, how it is
tested, the sandbox it runs in, when it takes over, how it fails safely, and what it never does. The design comes
from our project **Sorted** (household admin over Telegram); this document is about Saathi's voice assistant.
[LEARNING.md](LEARNING.md) gives the shorter overview, [PERSONAL_ABILITIES.md](PERSONAL_ABILITIES.md) the
presentation story for the three demo users.

---

## 1. The principle

**The AI learns and talks; compiled code handles the small repeated parts.** Saathi never hands a whole
conversation, call or decision to code. It hands over pieces that:
- repeat often enough to learn from;
- have a right answer that can be checked;
- are cheaper, faster or more reliable as code.

Three kinds of piece qualify:

| Kind | The repeated part | What the code produces | Who still does the talking |
|---|---|---|---|
| **Routine** (app-wide) | The taps of one phone task, e.g. booking an Uber Go | JSON steps, matched to the screen | The phone asks yes/no as always; the model still handles any screen the routine doesn't know |
| **Recogniser** (app-wide) | Mapping a request to an intent and slots ("cab to Saket" → `book_cab`, destination Saket) | `recognise(text) → {intent, slots}` or "not sure" | Spoken replies come from fixed templates; "not sure" goes to the model |
| **Personal ability** (one user) | One person's bookkeeping: logging a reading, a running total, a count-down, a threshold | `run({text, state, today}) → {say, state, urgent}` | The model words every reply from the result; only an urgent result is said as the code wrote it |

The shopping camera uses one fixed, hand-written check of the same kind (`app/abilities/food.py`): it measures the
sugar or salt in a pack against the user's condition and hands the camera model the finding. The model decides how
to nudge.

---

## 2. Where each kind lives

| Kind | Code | Training data | Stored |
|---|---|---|---|
| Routine | `app/skills/routine.py` (format, matcher, validator), `app/skills/compiler.py` (compile + replay test), `app/skills/engine.py` (lifecycle, serving) | Successful phone runs of one task (`skill_runs`, `skill_run_steps`) | `skills` table (body = routine JSON, sha256) |
| Recogniser | `app/skills/recogniser.py` | Every model answer to `/intent`, 30 days (`skill_samples`) | `skills` table (body = Python) |
| Personal ability | `app/abilities/__init__.py` (load, test, match, run, state) | That user's own repeated requests | Code: `fixtures/personas/abilities/<user>/<name>.py`; cases and history: `fixtures/personas/personas.json`; state: `ability_state` table, mirrored to Meko |
| Sandbox | `app/skills/sandbox.py` (static check, launcher), `sandbox_image/runner.py` (per-case runner), `sandbox_image/Dockerfile` | — | — |

---

## 3. The lifecycle

```
LLM handles it ──► candidate ──► tested ──► (family approves) ──► shadow ──► active
                       ▲                                                       │
                       └──────── degraded ◄──── fails a check / disagrees ─────┘
```

| Step | Routine | Recogniser | Personal ability |
|---|---|---|---|
| **Becomes a candidate** | `SKILL_MIN_RUNS` (3) successful model runs of the task, or new runs since the last compile | `SKILL_MIN_SAMPLES` (40) model answers | That user repeats the same kind of request; the demo users' abilities are seeded with this history |
| **Compiled by** | The strong model (`gemini-4-argon`, fallback `gemini-3.1-pro-preview`), or a heuristic for simple tasks | The strong model | The same loop; seeded for the demo |
| **Tested on** | 70% of runs shown to the compiler, **30% held out** (at least 1). Every visible run must replay; **90% of held-out runs** must replay | Held-out samples: **95% precision** on the ones it answers and **30% coverage** | Its cases, held-out ones included, in the sandbox **at every backend start**; all must pass |
| **Retries** | Up to 2 attempts, failures fed back to the compiler | Same | — |
| **Approval** | Family (Telegram button or dashboard), or `SKILL_AUTO_APPROVE=1` for demos | Same | Approved with the persona seed |
| **Shadow** | Runs beside the model; the model's answer is used. `SKILL_SHADOW_AGREE` (3) fully agreeing runs → active | `SKILL_CODE_SHADOW_AGREE` (20) agreements → active; 1 disagreement → degraded | — (tested at start instead) |
| **Active** | Serves steps; misses go to the model | Answers when sure; "not sure" goes to the model | Does the bookkeeping; the model words the reply |
| **Health checks** | Rolling window: a miss in **2 of the last 5** runs → degraded. **Canary:** 1 in `SKILL_CANARY_EVERY` (20) steps is also asked of the model in the background and compared | Output validated every call | Output validated every call (must be a dict with `say` and a dict `state`) |
| **Degraded** | The model takes over immediately; fresh runs compile v2 | Same | The model answers the request as before |
| **Retired** | Rejected versions, and older versions once a newer one is active | Same | — |

The compile jobs run in the background: routines every 10 minutes, recognisers every 30. `POST /skills/compile`
runs them now (demos, tests).

---

## 4. The safety layers, in order

Code written by a model is untrusted. Every Python skill and ability passes all of these, every time:

1. **Size cap:** 20 kB of source at most.
2. **Static check** (`static_check`), before anything runs:
   - imports only from `re, json, datetime, math, string, unicodedata, difflib, collections, itertools`;
   - none of `open, exec, eval, compile, __import__, input, breakpoint, globals, locals, vars, getattr, setattr,
     delattr, memoryview, help, exit, quit, dir, type, object`;
   - no dunder names or attributes (`__class__`, `__subclasses__`, …), so no reaching the interpreter through objects;
   - no `global`, `nonlocal`, `async`, `await` or `lambda`;
   - the expected entry function (`recognise`, `run`) must exist at top level.
3. **Fingerprint:** the SHA-256 of the code is stored when it is accepted and checked before every run, so a changed
   file or database row never runs silently.
4. **Out of process:** the code never runs inside the backend. `SKILL_SANDBOX` chooses where:
   - `docker` (the server): a fresh `saathi-skill-sandbox` container per job:
     ```
     docker run --rm -i --network none --read-only --tmpfs /tmp:size=4m --memory 128m --cpus 0.5 \
       --pids-limit 32 --cap-drop ALL --security-opt no-new-privileges --user 65534:65534 saathi-skill-sandbox
     ```
     No network, read-only disk, 128 MB, half a CPU, 32 processes, no capabilities, an unprivileged user.
   - `subprocess` (laptops): the same runner in a separate isolated interpreter (`python -I -S`), with a clean
     environment and the temp folder as its working directory. Same static check and tests, without the container
     limits.
   - `off`: compiled code never runs, and the model answers.
5. **One fresh interpreter per case:** module-level state can't leak between requests or tests.
6. **Time limits:** 10 s wall time per case; serving waits at most 3 s for the sandbox, then the model answers.
7. **Output limits:** at most 64 kB of output, parsed as JSON; anything else counts as a failure.
8. **Validation:** a routine must pass the Pydantic schema and the routine validator. A recogniser's intent must be
   a known intent. An ability must return `say` (text) and `state` (an object).
9. **Policy stays outside the code.** A routine can't skip safety:
   - Commit buttons (book, pay, order, send) must be wrapped in a `confirm` step. The validator checks this again,
     and the phone's `SafetyPolicy` asks yes/no regardless.
   - Routines can never use `open_app`, `open_deeplink`, `tap_xy`, `home` or `highlight`, dial codes, or PIN entry.
   - PIN, OTP and login screens stop the agent whatever the routine says.
10. **Failure is always safe:** any error, timeout, bad output or fingerprint mismatch means the model answers, as
    if the skill didn't exist, and the skill is marked degraded.

**Windows note (fixed 2026-11-01).** The runner used to read its input in the Windows locale, so any compiled code
containing Hindi text failed on Windows laptops. It now reads UTF-8.

---

## 5. Personal abilities in detail

A personal ability belongs to one user and does one small job for them. The six for the demo users:

| User | Ability | Its small job | Learned from |
|---|---|---|---|
| Ramesh (diabetes, Hindi) | `sugar_diary` | Store a reading, compare it with Dr. Kapoor's targets, average the last 3 | 11 conversations |
| | `medicine_refill` | Count metformin down two a day, reorder 5 days ahead | 7 |
| Sunita (BP, Hinglish) | `bp_log` | Store a reading, grade it, 7-day average, 180/120 urgent | 13 |
| | `salt_budget` | Add up today's salt from a frozen table of her usual foods | 9 |
| Arjun (blind, English) | `expense_log` | Store payments, total by period or category | 14 |
| | `bill_split` | Divide a bill with a tip, round up | 6 |

How one request flows (`POST /intent`, the phone's voice path):
1. The phone sends the utterance with `X-Saathi-Profile: sunita`.
2. The user's **active** abilities are checked by their trigger patterns (data, not code).
3. A match: the code runs in the sandbox with `{text, state, today}`, and returns the stored state and a result.
4. **The model writes the reply** (`ability_reply` route, prompt `ability_reply.md`). It gets the result plus what
   Saathi knows about the user, keeps every number exactly and answers in the user's style.
5. **Two exceptions:**
   - An urgent result (sugar under 70 or 300 and over, BP 180/120) is said exactly as the code wrote it: safety
     advice never waits on a model.
   - If the model fails, the code's own sentence is used.
6. No match, a degraded ability, or a sandbox failure: the normal intent model answers, as it always did.

Each ability's state (readings, totals, stock) is kept in the `ability_state` table and mirrored to that user's
Meko datapack after every change ([MEKO_STATE.md](MEKO_STATE.md)).

---

## 6. Cost, speed and privacy

- **Tokens:**
  - A routine step served by code costs no model call.
  - A recogniser answer costs no model call.
  - An ability saves the model doing the bookkeeping, but still uses one short call to word the reply. That's
    deliberate: we keep conversation quality over saving that last call.
- **Speed:** a sandboxed case runs in about 0.1–0.3 s on a laptop.
- **Privacy:** what the code computes stays on our server. The wording model sees only the result and what the user
  already shared. Screen text never goes to Meko or into prompts beyond the current step.
- **Visibility:**
  - `GET /skills` lists every app-wide skill version with its status, held-out pass rate, runs, and the tokens spent
    compiling it.
  - `GET /abilities` lists each demo user's abilities, with status, tests passed (held-out included), how many AI
    conversations each was learned from, and local runs.

---

## 7. What compiled code never does

- Hold a conversation, phone call or hospital call. The model does all the listening and talking.
- Decide anything irreversible (book, pay, send). That stays with the phone's yes/no confirmation.
- Read PINs, OTPs or passwords, or open links it built itself.
- Run inside the backend process, reach the network, or touch files.
- Keep running after a failed check. It is degraded at once and the model takes over.

---

## 8. Honest gaps

- The demo users' abilities are seeded, not compiled live from voice traces; compiling personal abilities uses the
  same loop as recognisers, but isn't wired to voice traces yet.
- Ability triggers are keyword patterns, so a request that only sounds like an ability can reach it. It then answers
  with its summary, and the model words the reply.
- `subprocess` mode on laptops has no container limits; production should run `SKILL_SANDBOX=docker`.
- Personal abilities have no shadow phase: they are tested at every start instead.
