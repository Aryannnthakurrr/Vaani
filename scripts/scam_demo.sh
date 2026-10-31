#!/usr/bin/env bash
# Scripted Scam Shield scenarios (SPEC section 7.6). Usage: scam_demo.sh [1|2|3|4|all]
set -euo pipefail
source "$(dirname "$0")/_env.sh"
require "$ADB"
emu() { "$ADB" emu "$@" >/dev/null; }
# `gsm accept` only answers on the network side; KEYCODE_CALL picks up the ringing call on the phone.
answer() { "$ADB" shell input keyevent KEYCODE_CALL; }
pause() { echo "   ...$1"; sleep "${2:-3}"; }

scenario1() {
  echo "1) Call-forwarding code SMS -> expect RED message alert"
  emu sms send 9000000001 "Courier on hold. Dial *401*9876543210 to reschedule"
}

scenario2() {
  echo "2) Unknown caller + OTP during the call -> expect RED in-call alert (spoken)"
  emu gsm call 9000000002; pause "ringing" 3
  answer; pause "on call" 4
  emu sms send 9000000003 "Your OTP for transaction is 482913. Do not share."
  pause "alert should play now" 10
  "$ADB" shell input keyevent KEYCODE_ENDCALL; emu gsm cancel 9000000002
}

add_contact() {  # add_contact "Name" number
  "$ADB" shell content insert --uri content://com.android.contacts/raw_contacts \
    --bind account_type:n: --bind account_name:n: >/dev/null 2>&1 || true
  local rid
  rid=$("$ADB" shell content query --uri content://com.android.contacts/raw_contacts --projection _id \
        | tr -d '\r' | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1)
  "$ADB" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$rid" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1" >/dev/null
  "$ADB" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$rid" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$2" >/dev/null
}

scenario3() {
  echo "3) Same flow but the caller is a saved contact -> expect NO red alert"
  add_contact "Beta Rohit" 9000000004
  emu gsm call 9000000004; pause "ringing" 3
  answer; pause "on call" 4
  emu sms send 9000000003 "Your OTP for transaction is 771204. Do not share."
  pause "no red alert expected" 8
  "$ADB" shell input keyevent KEYCODE_ENDCALL; emu gsm cancel 9000000004
}

scenario4() {
  echo "4) Unknown caller + payment app opens during the call -> expect in-call alert"
  emu gsm call 9000000005; pause "ringing" 3
  answer; pause "on call" 4
  if "$ADB" shell pm list packages | tr -d '\r' | grep -qx "package:com.google.android.apps.nbu.paisa.user"; then
    "$ADB" shell monkey -p com.google.android.apps.nbu.paisa.user 1 >/dev/null
  else
    echo "   GPay is not installed; sending the debug 'simulate foreground app' broadcast instead"
    "$ADB" shell am broadcast -a com.saathi.app.DEBUG_SIMULATE_FOREGROUND \
      --es package com.google.android.apps.nbu.paisa.user -p $PKG >/dev/null
  fi
  pause "alert should play now" 10
  "$ADB" shell input keyevent KEYCODE_ENDCALL; emu gsm cancel 9000000005
}

case "${1:-all}" in
  1) scenario1 ;;
  2) scenario2 ;;
  3) scenario3 ;;
  4) scenario4 ;;
  all) scenario1; pause "next" 8; scenario2; pause "next" 5; scenario3; pause "next" 5; scenario4 ;;
  *) echo "usage: $0 [1|2|3|4|all]"; exit 1 ;;
esac
