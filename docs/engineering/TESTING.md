# Testing Saathi — step by step

Everything below works with **no API keys** (mock mode). Run commands from the repo root in Git Bash.

## 0. One-time setup

- Set `JAVA_HOME` to a JDK 17 and `ANDROID_HOME` to the Android SDK (default `%LOCALAPPDATA%/Android/Sdk`).
  `scripts/backend_dev.sh` creates the backend venv in `backend/.venv` on its first run.
- On another machine, install Android Studio (it brings a JDK and the SDK) and Python 3.11+.

## 1. Start everything (emulator)

Terminal 1: the backend (mock mode, port 8000).

```bash
bash scripts/backend_dev.sh
```

Terminal 2: the emulator, then build, install and grant permissions.

```bash
bash scripts/emu_setup.sh
bash scripts/dev_install.sh
```

The app opens to **Home**. The emulator reaches the backend at `http://10.0.2.2:8000`, which is the default URL.
To check the backend, open Settings → Connection; it should say "✅ Mock mode".

**Talking without a microphone.** The emulator mic is unreliable, so debug builds accept typed speech:

```bash
adb shell am broadcast -a com.saathi.app.DEBUG_SAY --es text "'Apollo Hospital jaane ke liye cab book kar do'" -p com.saathi.app
```

The same command answers an open question ("Dr Mehta") or a yes/no prompt ("haan").

**Demo replay.** Settings → Developer → *Demo replay* makes the cab, shopping, doctor, house-help and guide tasks
run on the recorded screens in `fixtures/screens/`, so you don't need Uber, Amazon or Practo installed.
Turn it on before tests 3–6.

## 2. Automated tests

```bash
cd android && ./gradlew :app:testDebugUnitTest     # 104 JVM tests
cd backend && .venv/Scripts/python -m pytest -q    # 95 tests
```

## 3. Workflow tests (what to do → what you should see)

| # | Feature | Do this | Expect |
|---|---|---|---|
| 1 | **Scam: forwarding code** | `bash scripts/scam_demo.sh 1` | Full-screen red "Dangerous message" alert. "I'm safe" needs two taps. |
| 2 | **Scam: OTP during a call** | `bash scripts/scam_demo.sh 2` | The call is answered, then an OTP SMS arrives and a red "this call may be a scam" alert appears. **Hang up** ends the call. |
| 3 | **Scam: saved contact** | `bash scripts/scam_demo.sh 3` | No red alert, only an amber "Be careful" notification if a scam SMS arrived in the last 24 h. |
| 4 | **Scam: payment app in a call** | `bash scripts/scam_demo.sh 4` | Red alert: "A payment app opened during the call". |
| 5 | **Cab booking** (replay) | `DEBUG_SAY "Apollo Hospital jaane ke liye cab book kar do"` | Stop chip, then a confirm sheet with fare ₹184 and **Haan/Nahi**. After Haan: "Your cab is booked…". The Activity log lists each step. |
| 6 | **Safety gate** | Repeat 5 and tap **Nahi** | "Okay, I have stopped. Nothing was booked." The Activity log shows "you said no". |
| 7 | **Doctor + calendar** (replay) | `DEBUG_SAY "I need a heart doctor"`, then type "Mehta" in the answer box (or `DEBUG_SAY "Dr Mehta"`), then `DEBUG_SAY "haan"` | Three doctors are offered, the slot is booked behind the confirm sheet, and reminders appear under My updates. |
| 8 | **Shopping** (replay) | `DEBUG_SAY "order parle g biscuits"`; `DEBUG_SAY "Flipkart se Parle-G biscuit mangao"` | Amazon or Flipkart flow, ending in an "Add to your cart?" confirm. It never checks out. |
| 9 | **Guide mode** (replay) | Press Home, then `DEBUG_SAY "mujhe ye photo Aarav ko bhejni hai, kaise karun"` | A pulsing ring highlights Share → WhatsApp → Aarav → Send, then "Well done!". Nothing is tapped for you. |
| 10 | **Shopping camera** | Home → Shopping camera | After 2 frames a Parle-G card appears: MRP ₹140, amber "expires soon", spoken summary. "Order online" starts the shop task. |
| 11 | **Inbox digest** | `bash scripts/seed_inbox.sh`, then Home → My updates | Bills (Airtel ₹599, BSES ₹1,240), renewal, appointment, delivery, scam warnings, reminders. The OTP SMS is never sent to the backend (check `backend` logs: 12 classify calls for 13 SMS). |
| 12 | **Clinic call** (simulated) | My updates → "📞 Call the clinic for me". Type "Yes confirmed" → Send, then "About 20 minutes" → Send | Saathi introduces itself, asks for the wait, says goodbye, then speaks a summary and logs it. |
| 13 | **Welfare** | Settings → add a family member (any name, chat ID 123) → **Test welfare alert** | "Test alert logged on the server…" plus the message text. With `TELEGRAM_BOT_TOKEN` set it arrives on Telegram. |
| 14 | **Family dashboard** | Copy `dashboard_path` from the `/welfare/config` response (or the backend log) and open `http://localhost:8000<path>` in a desktop browser | Last seen, check time, family names and alerts. No chat IDs or OTPs. |
| 15 | **Offline safety** | `adb shell svc wifi disable; adb shell svc data disable`, then `scam_demo.sh 1` | The red alert still appears; it uses on-device rules only. |
| 16 | **Languages** | Settings → Language → हिन्दी / Hinglish / Auto | All screens and spoken lines switch. In Auto, Saathi mirrors the language of the last request. |
| 17 | **Dark mode** | `adb shell cmd uimode night yes` | Every screen readable in dark. |
| 18 | **Typed answers** | Any question overlay (e.g. 7) | Type in the box and press OK. Typed "haan"/"no" also answers confirm sheets. |

To record backup videos of 5, 9, 10 and the scam flow: `bash scripts/record_demos.sh all` (output goes to `docs/media/demo/`).

## 4. Real Android phone

**A. Plug in by USB.** This is the full experience: every permission is granted automatically.

1. On the phone, open Settings → About phone and tap **Build number** 7 times. Then go to Developer options and turn on
   **USB debugging**. On Xiaomi/Redmi/POCO also turn on **USB debugging (Security settings)**.
2. Connect the cable, accept the "Allow USB debugging?" prompt, and check that the phone is listed:
   `adb devices` (the emulator must be closed, or set `ANDROID_SERIAL` to the phone).
3. Run `bash scripts/backend_dev.sh` on the PC, then `bash scripts/dev_install.sh`. This builds, installs, grants
   every permission and runs `adb reverse tcp:8000 tcp:8000`, so the phone can reach the PC's backend.
4. In Saathi, go to Settings → Connection → Backend URL, enter `http://localhost:8000` and tap Save.
5. Real apps: install Uber, Practo, Amazon or Pronto and log in yourself. Turn **Demo replay OFF** so tasks
   drive the real apps (this needs `GEMINI_API_KEY` on the backend; without it, real-app tasks reply "I don't know
   how to do that yet").
6. Speak to it: tap the orange bubble or "Talk to Saathi". Without `SARVAM_API_KEY` it uses the phone's own speech
   recognizer and TTS.

**B. No cable: install the APK and run offline.**

1. Copy `android/app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow "install unknown apps").
2. Follow the in-app setup wizard. For the accessibility step on Android 13+, first go to **App info → ⋮ → Allow
   restricted settings**.
3. Settings → **Work offline (no backend)** uses the on-device fake backend, so intents, the scam shield,
   digest, camera simulation and the clinic call all work with no PC at all.
4. Or use the backend over Wi-Fi: start it on the PC (it listens on `0.0.0.0:8000`), allow Python through Windows
   Firewall, and set the Backend URL to `http://<PC Wi-Fi IP>:8000`.

**What works on a real phone with no keys:** the scam shield (real SMS and calls), the inbox digest from real
bank and bill SMS, welfare heartbeats, guide and replay demos, the camera simulation, the clinic call, and every safety
gate. **What needs keys:** Gemini to drive real third-party apps and read real product labels, Sarvam for Indian-voice
speech, Telegram for real family alerts.
