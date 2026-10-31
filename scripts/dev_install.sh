#!/usr/bin/env bash
# Build, install, and grant every permission so developers never click through settings.
# SKIP_BUILD=1 reuses the last APK.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
require "$ADB"

cd "$REPO_ROOT/android"
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  ./gradlew :app:assembleDebug
fi
APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" install -r -g "$APK"

ashell() { "$ADB" shell "$@"; }
ashell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow || true   # Android 13+ sideload restriction
ashell settings put secure enabled_accessibility_services $PKG/$PKG.core.agent.AgentService
ashell settings put secure accessibility_enabled 1
ashell cmd notification allow_listener $PKG/$PKG.features.inbox.NotifListener || true
ashell appops set $PKG SYSTEM_ALERT_WINDOW allow
ashell appops set $PKG SCHEDULE_EXACT_ALARM allow || true
ashell dumpsys deviceidle whitelist +$PKG >/dev/null || true
for p in RECORD_AUDIO CAMERA READ_PHONE_STATE READ_CALL_LOG RECEIVE_SMS READ_SMS \
         READ_CONTACTS READ_CALENDAR WRITE_CALENDAR POST_NOTIFICATIONS ANSWER_PHONE_CALLS; do
  ashell pm grant $PKG android.permission.$p 2>/dev/null || true
done
"$ADB" reverse tcp:8000 tcp:8000 || true   # a real device reaches the local backend at localhost:8000
ashell am start -n $PKG/.MainActivity >/dev/null
echo "Installed and granted."
echo "Backend URL: emulator http://10.0.2.2:8000, real device (adb reverse) http://localhost:8000"
