#!/usr/bin/env bash
# Run the mock Ambulance Dispatch API on :9000 (docs/CARE.md). It simulates a dispatch service; it never contacts
# a real one. Reads MOCK_AMBULANCE_API_KEY, SIM_SPEED_MULTIPLIER and GOOGLE_MAPS_API_KEY from the environment or .env.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -x backend/.venv/Scripts/python ]; then PY=backend/.venv/Scripts/python; else PY=backend/.venv/bin/python; fi
if [ -f .env ]; then
  while IFS='=' read -r key value; do
    value="${value%%#*}"; value="$(echo "$value" | xargs)"
    if [ -n "$key" ] && [ -z "${!key:-}" ]; then export "$key=$value"; fi
  done < <(grep -E '^(MOCK_AMBULANCE_API_KEY|SIM_SPEED_MULTIPLIER|GOOGLE_MAPS_API_KEY)=' .env)
fi
echo "Mock ambulance API on :9000 (speed x${SIM_SPEED_MULTIPLIER:-1})"
exec "$PY" -m uvicorn mock_ambulance.app:app --host 0.0.0.0 --port 9000
