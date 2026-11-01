#!/usr/bin/env bash
# Run the backend locally on :8000. MOCK=1 unless GEMINI_API_KEY is set (env or repo .env).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/backend"

if [ ! -d .venv ]; then
  PY="$(command -v python3.12 || command -v python3 || command -v python)"
  "$PY" -m venv .venv
fi
if [ -x .venv/Scripts/python ]; then VPY=.venv/Scripts/python; else VPY=.venv/bin/python; fi
# A uv-made venv has no pip: install with uv then (or skip; the packages are already there).
if "$VPY" -m pip --version >/dev/null 2>&1; then "$VPY" -m pip install -q -e ".[dev]"
elif command -v uv >/dev/null 2>&1; then uv pip install -q --python "$VPY" -e ".[dev]"
fi
# Chromium is only needed inside Docker (SANDBOX_ENABLED=1). Install it locally only on request.
if [ "${INSTALL_CHROMIUM:-0}" = 1 ]; then "$VPY" -m playwright install chromium; fi

if [ -f "$ROOT/.env" ]; then
  while IFS='=' read -r key value; do
    value="${value%%#*}"; value="$(echo "$value" | xargs)"
    if [ -n "$key" ] && [ -z "${!key:-}" ]; then export "$key=$value"; fi
  done < <(grep -E '^[A-Z_]+=' "$ROOT/.env")
fi
if [ -z "${GEMINI_API_KEY:-}" ]; then export MOCK=1; fi
echo "Starting backend on :8000 (MOCK=${MOCK:-0})"
# --reload is opt-in: on Windows a reload can leave the old worker serving on :8000.
RELOAD_FLAG=""; if [ "${RELOAD:-0}" = 1 ]; then RELOAD_FLAG="--reload"; fi
exec "$VPY" -m uvicorn app.main:app $RELOAD_FLAG --host 0.0.0.0 --port 8000
