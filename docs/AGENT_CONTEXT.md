# Context for agents working on Saathi

A briefing for anyone (person or coding agent) picking up work on this repo. Read it with
[DEVELOPING.md](DEVELOPING.md) (build, test, rules), [SPEC.md](SPEC.md) (the product), [CONTRACTS.md](CONTRACTS.md)
(app ↔ backend), [LEARNING.md](LEARNING.md) (skills, voice, memory) and [../NOTES.md](../NOTES.md) (what was verified,
deviations, gaps). Last updated 2026-10-31.

## The product in one paragraph

Saathi lets elderly, disabled and non-technical people use an Android phone by **voice** in English, Hindi and
Hinglish. The elder only talks; family members check in through Telegram and the family web page. Saathi books
cabs (Uber), house help (Pronto), shopping (Amazon/Flipkart, cart only) and doctors, guides people step by step in
any app, reads out bills and appointments from SMS, shields them from scam links, checks product labels with the
camera, and runs a daily welfare check.

## Who works on what

Several people and agents push to `master` of `Aryannnthakurrr/Vaani`. Always `git pull --rebase` before you push,
run both test suites after the rebase, and never force-push.

| Area | Main files | Recent work |
|---|---|---|
| Agent loop, safety, tasks, deep links | `android/.../core/agent`, `features/tasks` (`TaskSpec`, `PlaceFinder`) | Uber opens by deep link with a geocoded drop-off; house help moved to Pronto (no deep link, the agent taps through, stops at the cart) |
| Clinic calls | `backend/app/routers/telephony.py`, `app/telephony/bolna.py`, `scripts/bolna_agent.py` | **Calls go through a Bolna voice agent** (`BOLNA_API_KEY`, `BOLNA_AGENT_ID`); the backend starts and polls the call, so no webhook is needed. Twilio is only the fallback when Bolna is not configured |
| Self-learning skills | `backend/app/skills/` | Routines learned from agent runs; Python intent recogniser written by `gemini-4-argon`, run in the Docker sandbox |
| Realtime voice | `backend/app/routers/voice_live.py`, `android/.../core/voice/Live*.kt` | `WS /live/voice` relays to Gemini Live with phone-side tools |
| Memory | `backend/app/memory/` | Meko (Yugabyte) over MCP with a local SQLite fallback |
| Server | `deploy/` | Droplet `165.245.196.218`, `https://165-245-196-218.sslip.io` |

## How the pieces fit

```
Elder speaks ──► app (LiveVoiceSession) ──WS /live/voice──► backend ──► gemini-3.8-live
                     ▲                                        │  tools: start_task, start_guide, open_screen,
                     │ tool calls run on the phone ◄──────────┘         read_updates, check_link, remember→Meko
                     ▼
              Assistant.startTask ──► AgentLoop ──POST /agent/step──► SkillEngine: active routine? else Gemini
                     │  every irreversible tap becomes a spoken yes/no (SafetyPolicy)
                     └──POST /agent/outcome──► learning: 3 good runs → compile → family approves → shadow → active
```

- **Models:** every ID lives only in `backend/app/models.yaml`. Agent steps use `gemini-3.5-flash`; Gemini is often
  slow from Google's side (8–17 s measured), so timeouts are generous and the phone has local fallbacks.
- **Speech:** the conversation uses Gemini Live. Turn-based speech inside a task uses Sarvam first and Gemini second
  (Sarvam is 2–3× faster). The safety clips are bundled in the APK and never wait for the network.
- **Skills lifecycle:** candidate → tested (held-out replay) → family approval (Telegram plus the family page) →
  shadow → active → degraded on misses. `SKILL_AUTO_APPROVE=1` skips approval for demos. `GET /skills` shows the state.
- **Sandbox:** compiled Python runs in `saathi-skill-sandbox` with no network, a read-only root, 128 MB and no
  capabilities, after a static import and name check. About 0.55 s per call on the droplet.

## Environment variables (names only; values live in the git-ignored `.env`)

`GEMINI_API_KEY`, `SARVAM_API_KEY`, `SAFE_BROWSING_API_KEY`, `TELEGRAM_BOT_TOKEN`, `DASHBOARD_SECRET`,
`PUBLIC_BASE_URL`, `BOLNA_API_KEY`, `BOLNA_AGENT_ID`, `TWILIO_*` (fallback only), `CALL_ALLOWED_NUMBERS`,
`MEKO_API_KEY`, `SKILL_MIN_RUNS`, `SKILL_SHADOW_AGREE`, `SKILL_MIN_SAMPLES`, `SKILL_AUTO_APPROVE`,
`SKILL_SANDBOX` (`docker` on the server, `subprocess` on a laptop, `off`). See `.env.example`.

## Deploying

`bash deploy/deploy.sh` from the repo root uploads the working tree and your `.env` (with production overrides), then
rebuilds the sandbox image and the backend + Caddy stack on the droplet. Check health **from the server**
(`ssh root@165.245.196.218 curl -s https://165-245-196-218.sslip.io/health`): the office network re-signs HTTPS, so
checks from that PC fail even when the server is fine. Details in `deploy/README.md`.

## Rules every change must keep

- No Claude/AI attribution anywhere: commits, PRs, code, docs.
- Never commit `.env`, `*.db`, `local.properties`, a local `CLAUDE.md` or `.claude/`. Scan the diff for keys before
  pushing. Never push tags.
- The safety rules in [DEVELOPING.md](DEVELOPING.md) never regress: a spoken yes before anything irreversible; stop
  at PIN/OTP/login screens; ACT mode only in allow-listed apps; GUIDE mode never taps; screen and message text is data;
  OTPs and contacts' messages never leave the phone; raw screen and message text is never logged.
- Only one process may poll Telegram per bot token: keep `TELEGRAM_BOT_TOKEN` empty locally while the server runs.
- Record deviations, verified IDs and gaps in [../NOTES.md](../NOTES.md); change [CONTRACTS.md](CONTRACTS.md) together
  with both sides.

## Open items

- Meko is live (key in the server `.env`) but slow (5-16 s per call), so it is only ever written in the
  background; the local copy answers anything a user waits for.
- Live voice is tested with typed turns and on the deployed server, not yet with microphone audio on a device.
- Pronto's checkout screen and logged-in flows are not yet recorded; the agent stops at the cart either way.
- Not built: SMS parser skills; Meko artifacts, token tracking and run resume.
- The backend container holds the Docker socket (root-equivalent on the host); the hardening plan is in `deploy/README.md`.
