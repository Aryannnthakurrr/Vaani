#!/usr/bin/env bash
# Create (once) and boot the saathi_api35 emulator with the host webcam as the back camera.
set -euo pipefail
source "$(dirname "$0")/_env.sh"

AVD=saathi_api35
case "$(uname -m)" in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
IMAGE="system-images;android-35;google_apis_playstore;$ABI"

require "$SDKMANAGER"
if [ ! -d "$ANDROID_HOME/system-images/android-35/google_apis_playstore/$ABI" ]; then
  echo "Installing $IMAGE (one-time, about 1.5 GB)..."
  yes | "$SDKMANAGER" --licenses >/dev/null || true
  "$SDKMANAGER" "emulator" "platform-tools" "$IMAGE"
fi
if ! "$EMULATOR" -list-avds 2>/dev/null | tr -d '\r' | grep -qx "$AVD"; then
  echo "Creating AVD $AVD..."
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 --force
fi
# Let the PC keyboard type into the emulator (needed to log in to Uber, Practo, etc.).
# avdmanager writes "hw.keyboard = no" with spaces, so match either form.
CFG="$HOME/.android/avd/$AVD.avd/config.ini"
if [ -f "$CFG" ]; then sed -i 's/^hw.keyboard *= *no/hw.keyboard=yes/' "$CFG"; grep -q '^hw.keyboard *=' "$CFG" || echo "hw.keyboard=yes" >> "$CFG"; fi

if "$ADB" devices | tr -d '\r' | grep -q "^emulator-.*device$"; then
  echo "An emulator is already running."
else
  echo "Booting $AVD (webcam0 as back camera)..."
  "$EMULATOR" -avd "$AVD" -camera-back webcam0 -no-snapshot-save -no-boot-anim "$@" >/dev/null 2>&1 &
fi
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "Emulator ready. The backend is reachable from it at http://10.0.2.2:8000"
echo "Reminder: Uber, Practo and banking apps may misbehave on emulators."
echo "Use a real device for task flows and camera mode."
