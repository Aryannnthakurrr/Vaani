#!/usr/bin/env bash
# Record backup demo videos (SPEC §15) on a running emulator/device into docs/demo/.
# Needs: debug build installed via dev_install.sh, backend running, Settings → Developer → Demo replay ON.
# Usage: record_demos.sh [cab|guide|camera|scam|all]
set -euo pipefail
source "$(dirname "$0")/_env.sh"
require "$ADB"
export MSYS_NO_PATHCONV=1
OUT="$REPO_ROOT/docs/demo"
mkdir -p "$OUT"

say() { "$ADB" shell am broadcast -a com.saathi.app.DEBUG_SAY --es text "'$1'" -p "$PKG" >/dev/null; }
tap() { "$ADB" shell input tap "$1" "$2"; }
home_screen() { "$ADB" shell am start -n "$PKG/.MainActivity" --es route home >/dev/null 2>&1; sleep 4; }

# record <name> <seconds> <function>: runs the scenario while screenrecord captures it.
record() {
  local name=$1 secs=$2 fn=$3
  echo "Recording $name (${secs}s)..."
  "$ADB" shell "screenrecord --time-limit $secs --bit-rate 4000000 /sdcard/saathi_$name.mp4" &
  local rec=$!
  sleep 2
  "$fn"
  wait "$rec" || true
  ( cd "$OUT" && "$ADB" pull "/sdcard/saathi_$name.mp4" "$name.mp4" >/dev/null )
  "$ADB" shell rm -f "/sdcard/saathi_$name.mp4"
  echo "  -> docs/demo/$name.mp4"
}

cab() { home_screen; say "Apollo Hospital jaane ke liye cab book kar do"; sleep 22; say "haan"; sleep 15; }
guide() { "$ADB" shell input keyevent KEYCODE_HOME; sleep 2; say "Mujhe ye photo Aarav ko bhejni hai, kaise karun"; sleep 30; }
camera() { "$ADB" shell am start -n "$PKG/.MainActivity" --es route camera >/dev/null 2>&1; sleep 25; }
scam() {
  home_screen
  "$ADB" emu sms send 9000000001 "Courier on hold. Dial *401*9876543210 to reschedule" >/dev/null
  sleep 10; tap 540 1552; sleep 1; tap 540 1552; sleep 2
  "$ADB" emu gsm call 9000000002 >/dev/null; sleep 4
  "$ADB" shell input keyevent KEYCODE_CALL; sleep 4
  "$ADB" emu sms send 9000000003 "Your OTP for transaction is 482913. Do not share." >/dev/null
  sleep 14; tap 540 1575; sleep 4
}

case "${1:-all}" in
  cab) record cab_booking 50 cab ;;
  guide) record guide_mode 40 guide ;;
  camera) record shopping_camera 30 camera ;;
  scam) record scam_shield 55 scam ;;
  all) record cab_booking 50 cab; record guide_mode 40 guide; record shopping_camera 30 camera; record scam_shield 55 scam ;;
  *) echo "usage: $0 [cab|guide|camera|scam|all]"; exit 1 ;;
esac
