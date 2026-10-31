#!/usr/bin/env bash
# Send the demo SMS from fixtures/messages/demo_sms.json into the running emulator.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
require "$ADB"
PY="$(command -v python3 || command -v python)"

"$PY" "$REPO_ROOT/scripts/seed_inbox.py" "$REPO_ROOT/fixtures/messages/demo_sms.json" |
while IFS=$'\t' read -r sender text; do
  echo "-> $sender: ${text:0:60}"
  "$ADB" emu sms send "$sender" "$text" >/dev/null
  sleep 1
done
echo "Seeded. Open Saathi -> My updates to see the digest."
