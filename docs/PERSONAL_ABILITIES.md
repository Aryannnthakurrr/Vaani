# Personal compiled abilities: an assistant that grows a different skill set for each person

A write-up for presentations and docs (2026-11-01). Sections can be lifted onto slides as they are. Code paths are
there for anyone who wants to check a claim.

---

## 1. The idea in one slide

Most voice assistants send every request to a large AI model, every time. Saathi uses the AI to **learn**, then
hands repeated work to **small, tested pieces of code** that it compiled for that one person.

- **Faster:** an ability answers in a fraction of a second, with no model call.
- **Cheaper:** a request an ability handles costs no tokens.
- **More private:** a sugar reading or a BP number handled by an ability never reaches an AI provider.
- **Personal:** each person's abilities come from *their own* habits, in *their own* language.
- **Checkable:** every ability is plain Python with its own tests, some kept back as an exam the code never saw.

The learning loop comes from our project **Sorted**, which does this for household admin over Telegram (bills,
renewals, appointments). Saathi brings the same loop to a **voice** assistant for elderly, disabled and
non-technical people.

---

## 2. How an ability is born

```
1. The AI handles it         "aaj fasting sugar 148 aaya"  → the AI answers, and the request is kept as a trace
2. The pattern repeats       the same kind of request comes back again and again for this person
3. Code is compiled          a strong model writes a small Python function from those traces
4. Code is tested            static safety check + sandbox run on the traces it saw AND held-out ones it didn't
5. Family approves           nothing goes live on its own
6. Code takes over           the next request goes to the code first; the AI only handles what the code doesn't
7. Self-repair               if the code ever gives a bad answer, it is switched off and the AI takes over again
```

The safety rules for compiled code are the same as for Saathi's app-wide skills (`backend/app/skills/sandbox.py`):
- **Allowed imports only:** `re`, `json`, `datetime`, `math` and a few others.
- **No access** to files, the network, `eval`/`exec` or the interpreter.
- **Sandbox:** in production each run is a separate, locked-down Docker container. On a laptop it is a separate
  Python process.
- **Code fingerprint:** each ability's fingerprint (SHA-256) is checked before every run.

---

## 3. Three people, three different assistants

Each person has two abilities.

### Ramesh Sharma, 74, Lajpat Nagar, New Delhi
**Speaks** Hindi only. **Health:** type 2 diabetes (on metformin), knee arthritis.
**Family and doctor:** son Amit lives in Pune; his diabetes doctor is Dr. Neha Kapoor at Max Saket.
**Quirks:**
- Reports his sugar every morning before chai, like a diary.
- Wants the reply in Hindi even when he says "fasting".
- Calls Amit before he would bother the doctor.

| Ability | What it does | Learned from |
|---|---|---|
| **Sugar diary** | Logs the reading (fasting, after food or random) and checks it against *Dr. Kapoor's* targets (80–130 fasting, under 180 after food). Tells him the average of his last three readings. Below 70: take sugar now, re-check, call Amit. 300 or more: talk to the doctor today. | 11 conversations the AI handled |
| **Medicine stock** | Counts his metformin down two a day and says when Amit should reorder (5 days before the last tablet). "60 goli ka patta aaya" adds a new strip. | 7 conversations |

### Sunita Verma, 67, Sector 46, Gurugram
**Speaks** Hinglish, short sentences. **Health:** high blood pressure (on amlodipine).
**Family and doctor:** daughter Priya lives nearby; her doctor is Dr. Rakesh Gupta at Fortis.
**Quirks:**
- Checks her BP after the evening walk and says it as "146 by 92".
- Loves achaar and namkeen, and has asked Saathi to keep her honest about salt.

| Ability | What it does | Learned from |
|---|---|---|
| **BP diary** | Logs the reading, grades it (normal → stage 2 → crisis) against *Dr. Gupta's* target of 130/80 and keeps a 7-day average. 180/120 or more is always urgent: sit down, re-check in 5 minutes, call 108 or Priya if there's chest pain, breathlessness or dizziness. | 13 conversations |
| **Salt budget** | Adds up the salt in what she ate today ("do paratha aur achaar") against 5 g a day (WHO) and suggests a swap ("achaar ki jagah nimbu"). The AI's estimates for her usual foods were frozen into a table; new foods still go to the AI. | 9 conversations |

### Arjun Mehta, 41, Safdarjung Enclave, New Delhi
**Speaks** English. **Health:** totally blind, uses TalkBack.
**Family and budget:** wife Neha; a monthly household budget of ₹25,000.
**Quirks:**
- Every number must be spoken, and nothing may refer to colours or the screen.
- Pays everything by UPI and tells Saathi each payment.
- Splits the bill after Friday dinners.

| Ability | What it does | Learned from |
|---|---|---|
| **Spoken expense log** | "Spent 240 on groceries" is recorded. "How much did I spend this week?" gives a total by day, week, month or category, with the top categories, all spoken. | 14 conversations |
| **Bill splitter** | "Split 2350 among me and 3 friends with 10 percent tip" gives ₹647 each (total ₹2,585). | 6 conversations |

**The point for the audience:** the same assistant ended up with six different abilities, in three languages, for
three different lives. Nobody programmed "a diabetes feature"; it came from Ramesh's mornings.

---

## 4. The camera: sugar and salt warnings that depend on who is holding the phone

The shopping camera reads a pack live: name, MRP, quantity and expiry, plus the nutrition panel when it can see it.
**Fixed code**, not the AI's judgement, then decides whether the pack matters for *this* person
(`backend/app/abilities/food.py`):

| Who | Points the camera at | Hears |
|---|---|---|
| Ramesh (diabetes) | Frooti 200 ml | "ध्यान दीजिए, इसमें बहुत चीनी है: आम तौर पर इस पैक में लगभग 7 चम्मच…" (careful, very sugary, about 7 teaspoons in this pack; pick something sugar-free or ask the doctor) |
| Sunita (BP) | Lay's Classic 52 g | "Dhyaan dijiye, ismein bahut namak hai: … 0.9 gram namak, din ki limit ka 18%. … bhuna makhana ya chana…" |
| Arjun (no diet condition) | Either | No health comment, just the label |

- **Thresholds** are the UK FSA front-of-pack "high" values, the usual traffic-light reference:
  - sugar is high above 22.5 g per 100 g of food or 11.25 g per 100 ml of drink;
  - sodium is high above 600 mg per 100 g (1.5 g salt);
  - medium levels get a softer note.
- **Pack amounts in plain words.** "7 teaspoons" assumes 4 g of sugar per teaspoon. "18% of a day's limit" uses
  WHO's 5 g of salt a day.
- **If the panel can't be read,** typical printed values for common Indian packs (Frooti, Maaza, colas, Lay's,
  Kurkure, bhujia, Maggi, biscuits, sweets, pickles) fill in, and the warning says "typically".
- **Shown and spoken.** The warning appears in red or amber on the product card and is said once per product.
  "Read again" repeats it.
- **The person's own facts decide it.** Their conditions come from their profile and from what they told Saathi
  (stored in Meko), so the same Frooti is fine for Arjun and a warning for Ramesh.

---

## 5. Where each person's state lives (Meko)

| What | Where |
|---|---|
| Facts the assistant knows (name, home, family, doctor, health, preferences, routines) | The memory layer: local copy for instant reads, **mirrored to that person's own Meko datapack** |
| Each ability's state (sugar readings, BP readings, salt today, tablet count, expenses) | Local table `ability_state`, **mirrored to Meko** after every change as a message in that ability's conversation |
| The compiled code and its tests | `fixtures/personas/abilities/<person>/<ability>.py` and `fixtures/personas/personas.json` |

- **One datapack per person,** so one person's data can never show up in another's search. This was measured on
  Meko earlier, when two elders shared a datapack.
- **Readable names:** datapacks are named `saathi-persona-ramesh`, `-sunita` and `-arjun`.
- **Speed:** Meko writes take seconds, so nobody waits on them. Reads come from the local copy.

---

## 6. Running the demo on any of our laptops

The demo users are keyed by their plain profile ids (`ramesh`, `sunita`, `arjun`). They are not tied to a phone's
device id, a hash or a one-time setup.

- **Startup:** every backend seeds them from the repo when it starts, then tests every ability in its sandbox.
- **Same on every laptop:** each laptop's backend shows the same three people with the same history.
- **Seeded dates are relative** ("2 days ago"), so the history always looks recent.
- **A demo's own entries survive restarts.** `POST /abilities/<id>/reset` puts a person back to the seeded state
  before a demo.

On the phone:
1. Settings → Profile, pick Ramesh, Sunita or Arjun.
2. Speak, or type in debug builds:
   - Ramesh: "aaj fasting sugar 148 aaya", "metformin ki kitni goli bachi"
   - Sunita: "aaj BP 146 by 92 aaya", "aaj do paratha aur achaar khaya"
   - Arjun: "I spent 240 on groceries", "split 1840 between 4"
3. Open the camera and show a Frooti (Ramesh) or chips (Sunita). In mock mode (no Gemini key) the scripted camera
   shows exactly those packs for those people.

From a laptop, without a phone (the device id must be at least 8 characters):
```bash
curl -s localhost:8000/abilities
curl -s -X POST localhost:8000/abilities/sunita/run -H "X-Saathi-Device: demo-laptop" -H "Content-Type: application/json" -d "{\"text\": \"BP 146 by 92\"}"
```

`GET /abilities` lists everyone's abilities with:
- status;
- tests passed, including held-out ones;
- how many AI conversations each was learned from;
- local runs since then.

---

## 7. Honest scope (say this before someone asks)

- **The three people's abilities are seeded for the demo,** shown as the output of the learning loop after a few
  weeks. The loop itself (traces → compile → sandbox test → approval → takeover → self-repair) runs for real in
  Saathi's app-wide skills (`backend/app/skills`) and in Sorted. Compiling *personal* abilities from voice traces
  uses that same loop. The demo doesn't wait weeks for it.
- **The seeded abilities are still tested for real.** Every one passes its own tests, held-out cases included, in
  the sandbox each time a backend starts. If one fails, it is switched off and the AI answers instead.
- **Abilities pick requests by keyword.** Something that sounds like an ability but isn't (Ramesh asking to *order*
  sugar-free biscuits) can reach it. Live voice routing, where the AI chooses, is the next step.
- **Nutrition values:** a label read off the pack is used as printed. The fallback table has typical values, and
  the warning says "typically". It is a nudge, never medical advice.
- **Meko mirroring needs `MEKO_API_KEY` on the backend.** Without it everything works from the local copy.
