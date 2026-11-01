# Saathi healthcare: how it finds the right provider and makes sure the appointment is real

A knowledge-transfer write-up (2026-11-01). Each section is written so it can be lifted onto a slide or into a doc.
Code paths are given for anyone who wants to check a claim.

---

## 1. The one-line pitch

You tell Saathi what is wrong, in Hindi, English or Hinglish. In under a minute it decides whether this is an
emergency (and sends an ambulance) or an appointment (and phones nearby hospitals for you, asks the questions you
would ask, and books the slot you approve, while the hospital is still on the line).

---

## 2. The flow at a glance

```
You speak  ──►  Safety net (rules, instant)  ──►  red flag? ──yes──►  PATH A: emergency
   │                                                                   countdown → ambulance API → live tracking
   ▼ no
Intake nurse (AI, ≤ 3 questions)  ──►  Clinical intake note
   │                                       specialty · facilities · urgency · your preferences
   ▼
PATH B: appointment
  Find nearby places (Google Places, 8 → 15 → 25 km)
  Filter  (no phone, closed now, wrong type, hospitals you said no to)
  Score   (can they treat THIS need? + distance + reputation, weighted by urgency)
  Call the top 3 at once (AI voice agent), 2 backups
  Re-rank on what the hospitals actually said
  Recommend one, spoken aloud  ──►  you say yes  ──►  booked on that same call, or a booking call
  Calendar + reminders only if you say yes
```

---

## 3. Emergency detection (Path A)

**Two layers, so an emergency is never missed because an AI was slow or wrong.**

1. **Rule-based safety net** (`backend/app/care/safety_net.py`). Deterministic, no AI, runs on every sentence and
   every answer. 16 red flags in English, Hindi and Hinglish, including chest pain with warning signs, stroke signs
   (FAST), not breathing, unconscious, seizure, severe bleeding, poisoning, pregnancy emergency, self-harm,
   worst-ever headache, fever with stiff neck, vomiting blood, very low sugar, and a fall where the person can't
   get up.
   - It understands negations: "no sweating", "paseena nahi hai" does not trigger.
   - It judges combinations, not just words: e.g. a diabetic who is suddenly confused and sweaty.
   - It reads yes/no answers in context: "Is she breathing normally?" → "nahi" is a red flag.
   - Any hit skips the questions and goes straight to Path A.
2. **AI intake nurse** (`backend/app/llm/prompts/triage.md`). Asks at most 3 follow-up questions, red flags first.
   It answers one question explicitly: *"Could this be a medical emergency requiring an ambulance now?"* →
   YES / NO / UNCERTAIN, with reasoning and an acuity score (1 = most critical … 5 = least). When unsure it must say
   UNCERTAIN, which is treated like an emergency with a cancellable countdown.
3. **Asking outright** ("send an ambulance now") skips every question.

**What the user experiences.** A siren and a large countdown (15 s by default). Saying "I'm OK" or tapping cancels;
"send it" sends immediately; silence lets it run out and the ambulance is requested. Saathi never dials 108 by itself;
if anything fails it tells the user, in their language, to call 108 and alerts the family.

**The ambulance API** (`mock_ambulance/`, `backend/app/care/dispatch.py`). India's 108 has no public API, so we built
a stand-in for a private dispatch provider (the kind of API a CAD system or ambulance aggregator exposes). It is a
separate service on its own port with an API key, so swapping in a real provider means changing one file.

- Saathi sends a structured dispatch request: priority (P1–P4 from acuity), ALS or BLS (advanced life support for
  cardiac, stroke, breathing, trauma, unconscious), the GPS location and landmark, a category (CARDIAC, STROKE …),
  and a clinical handover in the paramedics' ATMIST format (age, time of onset, mechanism, injuries, signs,
  treatment).
- The mock assigns a vehicle and a crew, then simulates the trip on a real road route: RECEIVED → ASSIGNED →
  EN_ROUTE → ARRIVED_AT_SCENE → PATIENT_ON_BOARD → EN_ROUTE_TO_HOSPITAL → ARRIVED_AT_HOSPITAL → COMPLETED.
- The phone shows the ambulance moving on a map with ETA and distance, updated every second. A driver page
  (`mock_ambulance/driver.html`) shows the crew's side.
- "I'm OK" after dispatch cancels it (allowed until the patient is on board) and tells the family.
- "No units available" (503) is handled: the user is told to call 108 and the family is alerted.
- Family alerts go by Telegram and contain no symptoms; the crew gets the clinical details, the family gets "call
  them now".

---

## 4. Picking the right provider (Path B)

### 4.1 What the conversation feeds in

The intake note is built from **everything said in the conversation**, not just the first sentence: symptoms,
follow-up answers, history, medicines and allergies. Since this update it carries:

| Field | Example | Used for |
|---|---|---|
| `specialty` | orthopedics | search, filtering, scoring, the call |
| `search_queries` | "orthopedic hospital", "multispeciality hospital" | Google Places search |
| `required_facilities` | X-ray, MRI, ECG, dialysis, wheelchair access | scoring, asked on the call, re-ranking |
| `possible_conditions` | knee ligament injury | scoring |
| `acuity` + `appointment_timeframe` | 3, "today" | the score's weights, asked on the call |
| `preferences.max_fee_inr` | "not more than 500" | asked on the call, re-ranking |
| `preferences.insurance` | "Ayushman card" | asked on the call, re-ranking |
| `preferences.prefer` / `avoid` | "Max", "not Saroj" | bonus / removed before calling |
| `preferences.doctor_gender` | "lady doctor" | scoring, asked on the call |

If the AI is unavailable, a rule-based fallback still picks the specialty from what was said (a specialty the user
names, like "orthopedic", wins over a symptom word). Preferences need the AI.

### 4.2 The four steps

1. **Find.** Google Places text search for each query plus a nearby-hospitals search, widening 8 → 15 → 25 km until
   enough places turn up, with real driving times.
2. **Filter.** Drop places with no phone number, places closed right now (unless 24 h), places that are clearly
   the wrong kind (a dental clinic for a heart problem, an eye centre or a lab for anything else), and any
   hospital the user said they don't want.
3. **Score.** One batched AI call rates every place 0–1 on *"can this place see this patient for this need?"*. It
   gets the specialty, needed facilities, possible conditions, urgency and doctor preference, and judges from the
   place's name and Google type (a multispeciality hospital will have an MRI; a single-doctor clinic won't). A
   name-based rule fills in if the AI doesn't answer. Then:

   `score = capability × w1 + nearness × w2 + reputation × w3 (+ 0.15 if you asked for this hospital)`

   | Situation | capability | nearness | reputation |
   |---|---|---|---|
   | Urgent (acuity 1–2, or "today", "now", "within 6 hours") | 0.40 | **0.45** | 0.15 |
   | Normal | 0.45 | 0.35 | 0.20 |
   | Routine (acuity 4–5) | **0.50** | 0.25 | 0.25 |

   Reputation is the Google rating weighted by the number of reviews, so a 4.9 with 12 reviews doesn't beat a 4.5
   with 3,000.
4. **Call the top 3 at once**, with 2 backups that are called automatically if a line is busy, unanswered or a
   wrong number.

### 4.3 Verifying with the hospital (the part apps usually skip)

Google data is often stale, so Saathi doesn't trust it: an AI voice agent (Bolna, Sarvam voices) phones each
hospital in Hindi or Hinglish and asks, in order: is the specialist available for OPD, are the needed facilities
available (plus your insurance, fee limit, doctor preference), the next slots in your timeframe, the doctor's name,
the fee, appointment or walk-in, and insurance or Ayushman Bharat.

The agent shares only an age band and the complaint ("70s, F, knee pain after fall"), never the name, phone or
address, until the patient says yes to a slot.

An AI reads each call's transcript and extracts only what the hospital actually said (never guesses): specialty
confirmed, slots as real times, doctor, fee, walk-in, insurance, and whether every needed facility is there.

### 4.4 Re-ranking on real answers

After the calls, hospitals are ordered by:
1. specialist confirmed available;
2. **meets your needs**: needed facility missing, fee over your limit, or your insurance refused each push a
   hospital down;
3. earliest slot;
4. travel time;
5. fee;
6. rating.

Hospitals that refused, didn't answer, or said the specialist isn't available are dropped. The best option is
spoken aloud and shown: "Best option: Dr Anish (Orthopedics) at …, tomorrow 9 am, ₹1000, 12 min away."

### 4.5 How accurate is it? (honest version)

| Claim | Status |
|---|---|
| Emergency red flags caught by rules, no AI needed | Covered by unit tests in three languages |
| Right specialty from symptoms | AI nurse; rule fallback for named specialties and common symptoms |
| Wrong-type places filtered (dental, eye, lab) | Rule, tested |
| "Can treat this need" score | AI judgement from name and type only; not verified until the call |
| Facilities, slots, fee, insurance | **Verified by the hospital on the call**, not guessed |
| Preferences (fee, insurance, avoid/prefer, lady doctor) | Need the AI intake; not captured by the rule fallback |

The design principle: rank cheaply on public data, then **only trust what a human at the hospital confirmed**.

---

## 5. Making sure the appointment is real

- **Nothing is booked without your yes.** Spoken or tapped; this is enforced in code for every irreversible action.
- **Booking on the same call.** When a hospital offers a slot, the agent says "one moment, the patient is right
  here" and asks you on your phone. On your yes it books right there, gives your name and age, and asks for the
  token or booking number. If you don't answer in about 40 s, it says it will call back and the normal flow
  continues.
- **"Booked" only when the transcript proves it.** After the call, an AI reads the transcript; only a booking the
  hospital clearly confirmed counts. Otherwise you are told it's unclear and the normal recommendation follows. You
  are never told "booked" on a guess.
- **Booking call fallback.** If the yes comes later, a second call books exactly that slot and never accepts a
  different time on its own; it comes back to you.
- **Never pays, never reschedules, never cancels**, and never shares payment details or OTPs.
- **After booking:** "Shall I add it to your calendar and remind you a day before and two hours before?" Only on a
  yes.

---

## 6. Demo safety: real hospital names, test phone numbers

The hospitals, names, addresses and ratings on screen are **real** (live Google Places), but **no real hospital is
ever phoned during a demo**.

- `MASK_HOSPITAL_NUMBERS=1` (on by default): before calling, each hospital's real number is swapped for a test
  number (a teammate's phone from `DEMO_PHONE_MAP`, or the first number in `CALL_ALLOWED_NUMBERS`). The teammate
  answers as the receptionist. The screen keeps showing the real hospital.
- Even with masking off, only numbers on the `CALL_ALLOWED_NUMBERS` list can be dialled at all.
- 108 is never dialled automatically.
- Code: `backend/app/care/ranking.py` (`assign_call_numbers`, `mask_numbers`).

So the demo shows the real product end to end (real search, real ranking, real AI phone call, real booking
conversation) while the only phone that rings is ours.

---

## 7. What changed today

**The hospital calls weren't listening properly.** Reading the real call transcripts showed three causes:

1. The agent took **0.25 s** of silence as "they've finished". Receptionists pause mid-sentence ("Doctor Anish…
   kal subah 9 baje"), so the agent answered half-sentences. Now **0.7 s**.
2. **One word** ("haan", "ji") cut the agent off mid-question, so its own questions were chopped ("Ayushman Bharat
   ya—"). Now it takes **three words**.
3. The prompt now says: listen to the whole answer; if it's unclear or contradicts itself, ask again in other words
   (one misheard "nahi" used to end a call); repeat slots and fees back so the receptionist can correct them.

Both Bolna agents were updated in place (same IDs), so no `.env` changes are needed.

**"I'll call back" even after the patient said yes.** The new Roman-letters conversion was being awaited inside the
mid-call question, which can take seconds; Bolna dropped the request, so the agent told the hospital it would call
back while the patient was still being asked. The question now goes to the phone without waiting on that
conversion, and each request holds 5 s instead of 6. Live call lines are also converted in parallel instead of one
at a time.

**Provider matching now uses the whole conversation**: facilities, conditions, urgency and preferences (section
4), as above.

---

## 8. Known limits (say these before someone asks)

- Capability scoring before the call is based on the name and Google type; the call is what verifies it.
- Preferences are captured only by the AI intake, not the rule fallback.
- The ambulance API is a realistic mock; a real provider needs a commercial agreement.
- Masked demo calls reach a teammate, so call quality in the demo depends on that teammate playing receptionist.
- If the backend's public address changes (a new tunnel), re-run `scripts/bolna_agent.py --mode inquiry --update`
  so the mid-call tool reaches the right server.
