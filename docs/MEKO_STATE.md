# How Saathi uses Meko: one memory per person, where every fact knows who learnt it and where

A write-up for presentations and docs (2026-11-01). Code paths are given for anyone who wants to check a claim.

---

## 1. The idea in one slide

Saathi has many parts that learn about a person: the voice assistant, the doctor feature that phones hospitals, the
phone agent that books cabs, the inbox that reads bills, the shopping camera, and the compiled abilities. Most
assistants keep that in one flat list of facts, or forget it when the session ends.

Saathi keeps one **Meko datapack per person**:
- **Each part of Saathi writes as its own Meko agent,** and every fact records **where it was learnt**.
- **The facts are about the person,** so they outlive a session, an app reinstall or a new phone.
- **Any part of Saathi can use what another part learnt.** The doctor feature books Fortis; later, "book a cab for
  my doctor's appointment" goes to Fortis.

---

## 2. Who learns what (Meko agents)

| Meko agent | Part of Saathi | Learns from | Example fact (with `learned_from`) |
|---|---|---|---|
| `saathi-voice` | The voice assistant | What the person says ("remember that…") | "High blood pressure, on amlodipine 5 mg" (*told Saathi by voice*) |
| `saathi-care` | The doctor feature | Hospital calls and bookings | "Appointment with Dr. Rakesh Gupta (cardiology) at Fortis Memorial Gurugram, Sector 44, Mon 3 Nov, 11:00" (*booked while the hospital was on the line*) |
| `saathi-tasks` | The phone agent | Tasks it did in apps | "Books Uber Go (car) to Max Hospital Saket, never an auto" (*Uber bookings from Lajpat Nagar*) |
| `saathi-inbox` | Bills and messages | Summaries only, never message text | "Pays the electricity bill (DHBVN) around the 5th" (*DHBVN bill SMS, summary only*) |
| `saathi-camera` | The shopping camera | What they pick up in shops | "Keeps picking up namkeen" (*shopping camera at Galleria Market*) |
| `saathi-abilities` | Compiled abilities | Their running state | Sugar readings, BP readings, tablet count, expenses (one Meko conversation per ability, one message per change) |
| `saathi-family` | The family's setup | The dashboard | "Daughter Priya lives nearby in Gurugram" (*family setup on the dashboard*) |

How this maps onto Meko (`backend/app/memory/meko.py`):
- **Datapack:** one per person (`saathi-persona-sunita` for the demo users, a hashed id for real phones). Meko's
  search covers a whole datapack, so the datapack is the privacy boundary between people. We measured this on Meko:
  in a shared datapack, two elders saw each other's facts; with one each, neither did.
- **Agent:** each part of Saathi has its own long-lived "profile" conversation in that datapack, created by its own
  `agent_id`. Its facts hang off that conversation (`memory_add` with that `agent_id`).
- **Metadata:** each memory's metadata holds the topic, a key (so a newer fact replaces an older one), the agent and
  `learned_from`. For an appointment it also holds the structured parts: clinic, address, latitude and longitude,
  doctor, specialty, start time and booking reference.
- **Ability state:** each ability's state is a conversation per ability (`agent_id = saathi-abilities`), with one
  message for every change.
- **Screen tasks:** each phone task is a conversation (`saathi-tasks`), with one message per step: the action, the
  reason and which handler answered. Screen text is never sent, only its size and the app's package.

---

## 3. The appointment, end to end

1. **Sunita describes her symptoms.** The doctor feature finds hospitals and phones three of them.
2. **Fortis offers a slot.** She says yes while the receptionist is still on the line, and the agent books it.
3. **The care agent writes one fact** (`app/care/remembered.py`) to her datapack as `saathi-care`, with key
   `doctor/next_appointment`:
   - text: *"Appointment with Dr. Rakesh Gupta (cardiology) at Fortis Memorial Gurugram, Sector 44, Gurugram,
     Mon 3 Nov, 11:00"*;
   - metadata: `{clinic, address, lat, lng, doctor, specialty, start_iso, booking_ref, phone,
     learned_from: "booked while the hospital was on the line (Fortis Memorial Gurugram)"}`.
4. **Days later she says "book a cab for my doctor's appointment".**
   - **On the phone:** the cab task (`TaskRunner.resolvePlace`) looks for the appointment in the phone's own copy.
     If it isn't there (a reinstall, a new phone, or the booking was made elsewhere), it asks the backend
     (`GET /memory/appointment`).
   - **On the backend:** the endpoint reads the remembered fact and returns it only if it is still ahead.
   - **Result:** the cab goes to *Fortis Memorial Gurugram, Sector 44*, and Saathi says so before booking.
5. **The voice assistant knows it too.** The same fact is part of what Saathi knows about her (topic `doctor`), so
   the AI can say "your Fortis appointment is on Monday at 11" without anyone programming that line.
6. **A newer booking replaces it,** because the key is the same. A past appointment is ignored.

---

## 4. Seeing what Saathi learnt, and from where

`GET /memory/learned` (with `X-Saathi-Profile` for a demo user) lists every fact with its agent and source:

```json
{"topic": "doctor", "text": "Appointment with Dr. Rakesh Gupta (cardiology) at Fortis Memorial Gurugram, …",
 "agent": "saathi-care", "learned_from": "booked while the hospital was on the line (Fortis Memorial Gurugram)"}
{"topic": "preference", "text": "Trying to keep salt under 5 grams a day; loves achaar and namkeen",
 "agent": "saathi-camera", "learned_from": "shopping camera at Galleria Market: keeps picking up namkeen"}
{"topic": "routine", "text": "Pays the electricity bill (DHBVN) around the 5th of each month",
 "agent": "saathi-inbox", "learned_from": "DHBVN bill SMS (summary only)"}
```

This is the slide: one person, many parts of the assistant, each contributing what it learnt, all in one memory
that belongs to her.

---

## 5. Designed around Meko's speed

Meko writes take 5–16 s and a search 15–18 s (measured 2026-10-31). Nobody waits on them:
- **Writes** land in the local store at once and reach Meko in the background (`FallbackMemory`).
- **Reads** come from the local copy, which holds everything because it is written first.
- **Meko is the durable copy:** per person, shared by every backend on the same Meko account, so a new backend can
  recover from it.
- **Demo users are seeded on every backend start.** Seeding reads the datapack once (`memory_get_all`) and adds only
  what Meko doesn't have yet. Three laptops on one Meko account don't triplicate the facts, and a Meko outage on one
  start is made up on the next.
- **Datapacks are found by name** (`datapack_list`) when another backend already created them, so all laptops share
  one datapack per demo user.

---

## 6. Privacy rules that stay in force

- **One datapack per person,** so a search never crosses people.
- **Never stored:** OTP messages and messages from saved contacts never leave the phone, so they never reach Meko.
  The inbox agent stores summaries, never message text.
- **Refused facts:** the voice `remember` tool refuses anything with OTP, PIN, password, card or Aadhaar words, or
  long numbers.
- **No screen text:** for screen tasks, Meko gets the size and the app's package only.
- **Who sees what:** hospital calls get only the person's name from memory; family alerts get only the name.
  Nothing else crosses over (`AUDIENCES` in `app/memory/__init__.py`).
- **"Forget everything"** (`DELETE /memory`) deletes every topic locally and in Meko, plus the step history.

---

## 7. Verified on the live service (2026-11-01)

Seeding the three demo users through the real memory layer, then reading each datapack back with `memory_get_all`:

| Person | Datapack | Memories | Agents that wrote them |
|---|---|---|---|
| Ramesh | `saathi-persona-ramesh` | 11 | care (2), tasks (2), inbox, camera, abilities, voice, family (3) |
| Sunita | `saathi-persona-sunita` | 10 | care (2), voice (2), inbox, camera, abilities, family (3) |
| Arjun | `saathi-persona-arjun` | 10 | care (2), tasks (2), voice (2), abilities, family (3) |

One memory as Meko returns it (`memory_get_by_id`), trimmed:

```json
{"memory": "Appointment with Dr. Rakesh Gupta (cardiology) at Fortis Memorial Gurugram, Sector 44, Gurugram, Tue 03 Nov, 11:00",
 "metadata": {"key": "next_appointment", "kind": "doctor", "agent": "saathi-care", "clinic": "Fortis Memorial Gurugram",
              "address": "Sector 44, Gurugram", "lat": "28.4536", "lng": "77.0726", "doctor": "Dr. Rakesh Gupta",
              "specialty": "cardiology", "start_iso": "2026-11-03T11:00:00+05:30",
              "learned_from": "booked while the hospital was on the line (Fortis Memorial Gurugram)"}}
```

What the live service taught us, now handled in code:
- `memory_get_all` and `datapack_list` need a `conversation_id`, even though they cover the whole datapack.
- A memory's text comes back as `data` in listings and as `memory` by id.
- **The free tier caps datapacks per account** (`FREE_TIER_DATAPACK_LIMIT`). Saathi looks a persona's datapack up
  by name before ever creating one, so every laptop shares the same three.
- **Meko rate-limits a token that fires many calls at once** (`PAT_RATE_LIMITED`). Saathi sends at most two calls
  at a time and retries a rate-limited call after 5 s, then 20 s.
- A full seed of three people takes about five minutes in the background. Nothing waits on it.

---

## 8. Honest gaps

- The camera and inbox agents' facts for the demo users are seeded. In the running app, `saathi-care` (bookings),
  `saathi-voice` (remember) and `saathi-abilities` (state) write live; the camera and inbox don't write facts on
  their own yet.
- Reads come from the local copy for speed, so a fresh backend doesn't yet pull facts back from Meko. Meko is
  written to and checked, not read on the request path.
- Facts written before 2026-11-01 carry no agent or source and show as `saathi-voice`.
- **Datapack cap:** on the free tier, every real phone also needs a datapack (`elder-…`), and the account is near
  its cap. A new phone past the cap keeps working from the local copy, but its Meko writes fail (logged). A paid
  tier, or deleting old test datapacks, lifts this.
