# Saathi privacy audit

Generated 2026-11-01 02:57 by `scripts/privacy_audit.py` in 2.4 s. Every number below is measured by
running the code, not estimated. Re-run it any time; the same checks run in CI as tests
(`backend/tests/test_privacy.py`, `android/.../PrivacyTest.kt`, `PrivacyAuditTest.kt`).

## Headline

| Check | Result |
|---|---|
| Personal values found in server logs after 32 requests full of them | **0 of 26** |
| Secrets (OTPs, account, card, Aadhaar, PAN numbers, UPI IDs, amounts) that leave the phone | **0 of 22** |
| Forged or cross-device requests blocked | **16 of 16** |
| Links checked without their query, tokens or IDs | **10 of 10** |
| Numbers left in a scam alert to family / symptoms in an emergency alert | **0** / **none** |

## 1. On the phone: what leaves

Each message goes down the same path as a real notification: private values go into the on-phone vault as tokens
(`⟦P12⟧`), OTP messages and messages from saved contacts are never sent at all, and the network layer redacts once
more. The secret values are then searched for in exactly what would be sent.

| | |
|---|---|
| Secrets in 12 real-style SMS, email and WhatsApp messages | 22 |
| Secrets that would leave the phone | **0** (100% kept on the phone) |
| Messages that never leave the phone at all (OTPs, saved contacts) | 3 of 12 |
| Bytes of message text read / sent | 1088 / 792 |

Found and fixed by this audit: UPI IDs at the end of a sentence (`…to ramesh.k@okaxis.`) and an account number
repeated inside a link (`?acc=20123456789`) used to slip through: 3 of 22 secrets before, 0 after.

## 2. On the server: nothing personal in the logs

32 requests carried every secret above, raw (as if the phone's redaction had failed), plus a patient's
name, phone number and address, through intent, classification, appointment extraction, the digest, link checks,
screen-task steps, the doctor intake, welfare setup and scam alerts (9 of 9 routes
answered normally, so the data really went through them). All server logging was captured at DEBUG level
(83 lines). Personal values found in it: **0**.

## 3. Only the phone that owns the data can read it

Each install makes a 32-byte secret on first launch and sends it with every call (a header, never the URL). The
server keeps only its SHA-256 hash. Before this change, knowing a device ID was enough to read its doctor session
or change its family contacts.

| Attempt | Route | Status | Blocked |
|---|---|---|---|
| device id without its key | `GET /usage` | 401 | ✅ |
| device id without its key | `GET /care` | 401 | ✅ |
| device id without its key | `POST /welfare/config` | 401 | ✅ |
| device id without its key | `POST /scam/notify_family` | 401 | ✅ |
| device id with a guessed key | `GET /usage` | 401 | ✅ |
| device id with a guessed key | `GET /care` | 401 | ✅ |
| device id with a guessed key | `POST /welfare/config` | 401 | ✅ |
| device id with a guessed key | `POST /scam/notify_family` | 401 | ✅ |
| no credentials | `GET /usage` | 401 | ✅ |
| no credentials | `GET /care` | 401 | ✅ |
| no credentials | `POST /welfare/config` | 401 | ✅ |
| no credentials | `POST /scam/notify_family` | 401 | ✅ |
| another real device | `GET /usage` | 200 | ✅ |
| another real device | `GET /care` | 404 | ✅ |
| another real device | `POST /welfare/config` | 403 | ✅ |
| another real device | `POST /scam/notify_family` | 403 | ✅ |

The real phone still works: **yes**.

## 4. Links: checked without the personal parts

The phone and the server both strip a link before it is checked or opened in the sandbox: the query string,
fragment and login are dropped, and token-like parts (reset tokens, file IDs, UUIDs, phone and account numbers)
become `…`. Short-link codes stay so redirects can still be followed. 8 of 10
test links had personal parts; 10 of 10 come out exactly as expected
(437 → 265 characters sent).

| Link in the message | What the checker sees |
|---|---|
| `https://x.in/reset?token=abc123` | `https://x.in/reset` |
| `https://bank-kyc.co/verify?acc=1234567890&otp=4455#step2` | `https://bank-kyc.co/verify` |
| `https://bit.ly/3xYz9Ab` | `https://bit.ly/3xYz9Ab` |
| `https://pay.example.com/p/8f14e45fceea167a5a36dedd4bea2543/confirm` | `https://pay.example.com/p/…/confirm` |

## 5. Family alerts carry no medical or money details

- Emergency alert: "an ambulance has been requested, please call them now". Symptoms: **not included**.
- Scam alert, with numbers stripped: _Caller asked her to send … to a/c… and share OTP… from +…_

## 6. What is kept, and for how long

| Data | Kept |
|---|---|
| Doctor (care) session on the server | 6 hours |
| Hospital call transcripts | dropped once booked or cancelled |
| Screen-task step history | 7 days |
| Messages on the phone | 30 days |
| Private-values vault on the phone | 30 days |
| "Forget everything" | `DELETE /memory` (all remembered facts and task steps) |

## 7. Also changed

- Settings: the agent stops on any Settings page about security, accounts, apps, developer options, location,
  reset and similar, and may not tap those rows on the Settings home list. Display, text size, dark mode and sound
  still work.
- Screenshots and screen recording are blocked (`FLAG_SECURE`) on the digest, alerts, doctor, activity-log and
  settings screens, so screen-sharing scam apps see a black screen.
- Hospital inquiry calls hear an age band ("70s, F"), never the exact age.
- Message, email and screen text is redacted at the network layer for classification, appointment extraction and the
  digest, whatever the caller did before.
