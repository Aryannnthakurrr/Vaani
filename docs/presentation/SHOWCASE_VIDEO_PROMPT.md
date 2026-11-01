# Showcase video: the prompt, the facts and the visuals

Written 2026-11-01 from every document in this repo:
- the product spec (SPEC);
- what was built and verified (NOTES);
- the feature docs: APP_CASE_STUDIES, CARE, HEALTHCARE_KT, PERSONAL_ABILITIES, MEKO_STATE, COMPILED_CODE, LEARNING;
- privacy: PRIVACY_PITCH, PRIVACY_AUDIT;
- the month-long SIMULATION, UI_AUDIT, TESTING, CONTRACTS and deploy/README;
- our sister project Sorted.

Use it in one of three ways:
- **Paste section 1** into an AI script or video tool (ChatGPT, Gemini, Claude, Runway, Synthesia, HeyGen, Pictory).
- **Give sections 1 and 3** to whoever makes the visuals. The video uses generated visuals only, no app screenshots.
- **Check claims against section 2.** Every number in it comes from a doc in this repo.

The app is called **Vaani** (वाणी) on screen and in speech. The code and repo still use the earlier name, Saathi. The
video should only say Vaani.

---

## 1. The prompt (copy everything inside the block)

```text
You are a scriptwriter and video director for a product showcase. Write a complete shooting script for a
7–8 minute showcase video of VAANI, plus a 90-second cut and a 30-second teaser. Use ONLY the facts below.
Do not invent numbers, partners, users, prices or features. Where a fact says "demo", "mock" or "seeded", the
video must not present it as live production.

FORMAT
For every scene give:
- the scene number and title, and its duration;
- VOICEOVER: short spoken sentences, read at an easy pace;
- ON SCREEN: generated visuals only (see VISUAL STYLE). No screenshots or screen recordings of the app;
- SUPERS: on-screen text, at most 8 words;
- SOUND: music changes, app voice lines, the siren.
Then give:
- YouTube chapter titles;
- the 90-second cut, as a list of the scenes and lines to keep;
- the 30-second teaser;
- a shot list of every generated visual, each with a one-paragraph prompt for an image or video generator.

TONE AND AUDIENCE
- Judges, investors and engineers will watch it.
- Warm and human first, technical second. Open on a person, not on technology. Show, then explain.
- Narration is in English. Show the app's real Hindi and Hinglish voice lines with subtitles.
- Calm music. No hype words ("revolutionary", "game-changing", "seamless").
- Be honest about what is a demo. Honesty is part of the pitch.

VISUAL STYLE (no app UI anywhere; every frame is generated)
- The video is about the FEATURES, not the interface. Never show screenshots, screen recordings or mock-ups of
  the app's screens.
- Use three kinds of generated visuals:
  1. ILLUSTRATED PEOPLE AND PLACES: a warm, flat 2D illustration style with soft grain, Indian settings (a Delhi
     flat in the morning, a Gurugram park at dusk, a kirana shop aisle, a hospital reception desk). Ramesh,
     Sunita and Arjun are consistent characters across every scene.
  2. MOTION-GRAPHIC EXPLAINERS: clean diagrams that build step by step. White background, black lines and
     type, one teal accent (#0F766E), square-ish corners, no gradients or shadows. Arrows, flows, checklists
     and tables animate in as the narrator mentions them.
  3. KINETIC TYPE AND DATA: big numbers and short lines of type for the measured facts ("0 of 22 secrets
     leave the phone"), and the person's spoken line as an animated speech bubble with its English subtitle.
- Represent Vaani as an abstract voice: a soft teal waveform or ring that pulses when it speaks or listens. It
  is never a robot, a face or a phone screen.
- Represent apps abstractly: a generic "cab app" and "house-help app" drawn as simple shapes (rows of labelled
  boxes for Uber-style text, a blurred picture with a crosshair for Pronto-style vision). No real brand logos
  or copied app screens.
- Represent safety as a checkpoint: a teal gate or shield that an AI suggestion must pass before anything
  happens, with a "haan?" bubble at every irreversible step.
- Represent memory as one glowing folder per person, with seven small labelled streams (voice, care, tasks,
  inbox, camera, abilities, family) flowing into it.
- Represent compiled code as a small, glowing block of code that is put inside a sealed glass box (the
  sandbox), tested against cards, then stamped "approved".
- Every scene below has a VISUAL line. Follow it, and describe each shot precisely enough that an AI image or
  video generator could produce it.

WHAT VAANI IS
Vaani is an Android app plus a cloud backend. It lets elderly, disabled and non-technical people use their
phone by talking, in English, Hindi or Hinglish. It:
- operates other apps for them: Uber cabs, Pronto house help, an Amazon or Flipkart cart;
- teaches them to use any app themselves;
- finds and books a doctor by phoning hospitals for them, and handles emergencies;
- warns them about scams, before and during calls;
- reads out their important messages each morning;
- checks food packs with the camera;
- learns small personal skills for each person.
The person only talks. Family stay in the loop through Telegram and a family web page.
The rule behind everything: the AI suggests, the phone's own code decides, and nothing irreversible happens
without the person's own "haan" (yes).

WHO IT IS FOR, AND WHY IT IS DESIGNED THIS WAY
- The people Vaani serves already hand their phones to neighbours, shopkeepers and "helpful" callers.
- They are the main targets of fake KYC texts, "your account will be blocked" calls, screen-sharing tricks and
  the "your grandson is in trouble, send money now" message.
- Most can't read a long permission screen or spot a lookalike link.
- Doing nothing doesn't keep them safe. It leaves them to strangers.
- Vaani is designed voice first with big text always: body text at least 22 sp, buttons at least 64 dp, a
  calm black-and-white look with one teal accent, and full TalkBack labels.
- Before it listens, it plays a short tone, so the person knows it is their turn to speak.
- If the person says nothing, it asks again for up to about a minute, so a blind user is never left with only
  buttons.
- It replies in the same language style the person used, and remembers "mujhe English nahi aati" for good.
- When a place name is ambiguous ("Apollo Hospital": Delhi or Faridabad?), it asks once instead of guessing.

STRUCTURE (follow this order; durations are a guide)

1. HOOK (0:00–0:25)
   VISUAL: illustrated close-up of an older woman's hands holding a phone, a cab app's tiny buttons swimming out of focus; the phone buzzes with an unknown caller and an "OTP" bubble floats up in red. Cut to black, then the teal Vaani waveform breathes in.
   - A grandparent who can't book a cab. A scam caller asking for an OTP.
   - Line: "Phones were not built for her. Vaani is."

2. MEET THE THREE PEOPLE (0:25–1:00)
   VISUAL: three illustrated portraits in their homes (Ramesh with chai and a glucometer at dawn; Sunita with a BP cuff after her walk, a jar of achaar nearby; Arjun with a white cane and earphones). Each gets a label card: name, age, language, condition, quirk. The teal waveform splits into three, one per person.
   Vaani is shown through three demo users. Each stands for a different kind of user and gets a different
   assistant.
   - RAMESH SHARMA, 74, Lajpat Nagar, Delhi. An older person living with a chronic condition, who likes
     routine and family close by.
     - Speaks only Hindi. Type 2 diabetes, on metformin; knee arthritis.
     - Reports his sugar every morning before chai, like a diary.
     - Son Amit lives in Pune. His doctor is Dr. Neha Kapoor at Max Saket.
     - He calls Amit before he would bother the doctor.
   - SUNITA VERMA, 67, Gurugram. An independent person managing her condition through diet.
     - Speaks Hinglish, in short sentences. High blood pressure, on amlodipine.
     - Checks her BP after her evening walk ("146 by 92").
     - Loves achaar and namkeen, and asked Vaani to keep her honest about salt.
     - Daughter Priya lives nearby. Her doctor is Dr. Rakesh Gupta at Fortis.
   - ARJUN MEHTA, 41, Delhi. A younger blind person for whom everything must be spoken.
     - Speaks English. Totally blind; uses TalkBack.
     - Every number must be spoken, and nothing may refer to colours or the screen.
     - Pays everything by UPI, keeps a ₹25,000 monthly household budget, and splits the bill after Friday
       dinners.
   - Picking a person in Settings → Profile changes the name, language, memories, appointments and skills.
   - Line: "Same app. Three people. Three different assistants."

3. HOW VAANI OPERATES OTHER APPS: TWO CASE STUDIES (1:00–2:20)
   VISUAL: motion graphics only. Uber: a generic cab-app outline whose buttons turn into a numbered text list (1 "Where to?", 2 "Uber Go ₹184", 3 "Book") that the AI reads; a loop diagram Look → AI picks one action → Code checks → Act; the "Book" box hits the teal gate and a "Book karun?" bubble appears. Pronto: the same outline, but some boxes are blank; a camera-shutter flash, a blurred picture of the screen, a crosshair landing on "Go to cart" with x,y numbers; the gate checks what is under the crosshair. End on a split screen: "reads text" vs "reads text + picture".
   Vaani has no private access to Uber or Pronto. It uses the same screen a person sees, read through
   Android's accessibility service. When that isn't enough, it adds a screenshot.

   CASE 1: UBER, AN APP THAT DESCRIBES ITS SCREEN
   - Vaani reads every element on screen: its text, label and hint, whether it can be tapped or typed into,
     and where it is. That becomes a numbered list the AI reads.
   - Vaani's own code, never the AI, builds the Uber deep link with the destination already set.
   - Then it loops: look at the screen, the AI picks ONE action, the phone's code checks it, then it acts.
   - "Cab" means a car, so it ignores Moto, Bike and Auto rows unless the person asked for one.
   - Before Book, Pay, Confirm or Add to cart, it stops and reads out the ride and fare, e.g. "Uber Go, ₹184.
     Book karun?" Only a spoken or tapped yes continues.
   - If the AI tries to tap Book directly, the code turns that tap into a yes/no question.
   - Walk through "Saket ke liye cab book kar do", showing who decides each step: the person, the code or the
     AI.

   CASE 2: PRONTO (HOUSE HELP), AN APP THAT HIDES PARTS OF ITS SCREEN
   - Pronto's buttons are in the accessibility tree. But when you tap "Add", the change to "Added" and the
     green "Go to cart" bar never reach it, and its lists often don't scroll when asked. It also has no
     usable deep link, so Vaani taps through from the home screen.
   - So for Pronto, Vaani also takes a screenshot every step. It hides its own overlays first and shrinks the
     picture to at most 1080 px.
   - The AI looks at the picture and taps by screen coordinates.
   - Code still blocks any coordinate tap that has no screenshot behind it. It also checks what lies under the
     point for words like "Pay".
   - Vaani asks the person the questions the app asks ("30 minutes ₹40, 60 minutes ₹79 or 90 minutes ₹119?"),
     adds the visit to the cart after a yes, and stops there. The person pays in Pronto themselves.
   - Show a split screen: Uber is text; Pronto is text plus a picture.

   SAFETY RULES THAT HOLD IN BOTH
   - It acts only in a short allowlist of apps: cabs, shopping carts, doctor booking, house help, calendar.
     Never banking, UPI, WhatsApp or email.
   - It stops at PIN, OTP, CVV, password and login screens; the person types there.
   - It never types secrets or dial codes, and it opens only links its own code built.
   - In Settings it may change text size, display and sound, but stops at security, accounts and reset pages.
   - A task stops after 25 steps, and the person can say stop at any time.
   - Screen and message text is data, never instructions. A hidden "ignore your rules" line in an SMS is
     treated as a scam. Even if the AI is fooled, the code refuses the action.

4. "SHOW ME HOW": GUIDE MODE (2:20–2:40)
   VISUAL: an abstract phone outline with a pulsing teal ring moving from one shape to the next; an illustrated finger (Ramesh's) does the tapping; a small "Well done!" burst at the end.
   - "Mujhe ye photo Aarav ko bhejni hai." A pulsing ring appears around the next button, and Vaani speaks
     the step.
   - The person does every tap; in this mode Vaani never taps. At the end: "Well done!"
   - Line: "Vaani can do it for you, or teach you to do it yourself."

5. ONE MEMORY, MANY AGENTS: APPOINTMENT, THEN A CAB TO IT (2:40–3:20)
   VISUAL: Sunita's glowing memory folder; seven labelled streams flow in. The care stream drops a card "Fortis, Sector 44, Tue 11:00 — learnt while the hospital was on the line" with a map pin. Time-lapse (calendar pages flip), then her speech bubble "doctor appointment ke liye cab" pulls that card out and a stylised cab drives along a line to the pin.
   - Each person has their own memory datapack in Meko, so one person's facts never show up in another's
     search.
   - Each part of Vaani writes to it as its own named agent: voice, care (doctor), tasks (apps), inbox,
     camera, abilities and family.
   - Every fact records where it was learnt. For example: "Appointment with Dr. Rakesh Gupta at Fortis
     Memorial Gurugram, Sector 44, Tue 3 Nov, 11:00". It was learnt by the care agent "while the hospital was
     on the line", and its address and map location are attached.
   - Days later, Sunita says "Mere doctor appointment ke liye cab book kar do". She never says where. The cab
     goes to Fortis, Sector 44, because the doctor feature remembered it. Vaani says so before booking.
   - It still works after a reinstall or on a new phone, because it asks the backend for the remembered
     appointment.
   - The voice assistant knows it too: "Your Fortis appointment is on Tuesday at 11."
   - Show the "what Vaani learnt" list, with columns fact, agent and source. Seven agents, one memory, and
     it belongs to her.

6. THE DOCTOR FEATURE (3:20–4:50)
   VISUAL: a two-path flowchart (emergency left in red, appointment right in teal). Emergency: a big countdown ring 15 → 0, a siren icon, a map with an ambulance dot gliding along a road and an ETA ticking down. Appointment: a map with hospital pins appearing in widening rings (8, 15, 25 km), some greyed out with a reason tag ("no phone", "dental", "closed", "you said no"); score bars fill for the rest; three phone-call lines fan out at once with Hindi speech bubbles; check marks or crosses land beside "MRI", "Ayushman", "fee ≤ ₹500"; the list re-orders. A hold-music note, a "Book karun?" bubble, a "haan", and a ticket stamped with a token number. Honesty card: a hospital pin whose phone number is swapped for "our test phone".
   a) EMERGENCY FIRST
      - Every sentence goes through a rule-based safety net with 16 red flags, in English, Hindi and
        Hinglish. For example: chest pain with warning signs, stroke signs, not breathing, unconscious,
        seizure, heavy bleeding, poisoning, very low sugar in a diabetic, a fall where they can't get up.
      - It understands negations ("no sweating", "paseena nahi hai") and judges combinations of symptoms.
        It needs no AI, so a slow AI never delays an emergency.
      - Then an AI intake nurse asks at most 3 questions, red flags first. It must answer "Could this be an
        emergency?" with YES, NO or UNCERTAIN. UNCERTAIN is treated as an emergency.
      - An emergency means a siren on the alarm channel, so it sounds even in silent mode, and a large
        15-second countdown. "I'm OK" cancels; "send it" sends at once.
      - Then a structured ambulance request: a priority from P1 to P4, advanced or basic life support, GPS
        and a landmark, and a paramedic-style handover (ATMIST).
      - The phone shows the ambulance moving on a live map, with the ETA updating every second. The crew's
        own page shows the clinical details.
      - India's 108 has no public API, so this is our own realistic dispatch API, built so a real provider
        can be swapped in. Vaani never dials 108 by itself; "Call 108" opens the dialler.
      - If no ambulance is available, the person is told to call 108 and the family is alerted. The family
        Telegram alert contains no symptoms.
   b) NOT AN EMERGENCY: FINDING THE RIGHT HOSPITAL
      - The intake note is built from the WHOLE conversation:
        - the specialty;
        - the facilities needed (X-ray, MRI, ECG, dialysis, wheelchair access);
        - the possible condition and the urgency;
        - preferences: "fee under 500", "Ayushman card", "not that hospital", "lady doctor".
      - Find: real places from Google, widening the search from 8 to 15 to 25 km, with real driving times.
      - Filter out places with no phone number, places closed now, places of the wrong kind (a dental clinic
        for a heart problem), and hospitals the person said no to.
      - Score each place on three things: can it treat this need, how near it is, and its reputation (rating
        weighted by the number of reviews). Urgent cases weigh nearness most; routine cases weigh capability
        most.
   c) THE BACKGROUND CALLS
      - Vaani's AI voice agent phones the top 3 hospitals AT THE SAME TIME, in Hindi or Hinglish. 2 backups
        are called if a line is busy or unanswered.
      - It asks:
        - is the specialist available for OPD?
        - are the facilities you need actually there?
        - is your insurance accepted, and is the fee within your limit?
        - what are the next slots, and who is the doctor?
      - It shares only an age band and the complaint ("70s, F, knee pain") until the patient says yes.
      - An AI reads each transcript and keeps only what the hospital actually said.
      - Hospitals that miss a need drop down the list; those that refuse or lack the specialist are dropped.
      - Line: "Rank cheaply on public data, then only trust what a human at the hospital confirmed."
   d) BOOKING WHILE THE HOSPITAL IS ON THE LINE
      - When a hospital offers a slot, the agent puts them on hold and the patient's phone asks out loud,
        with the doctor, hospital and time, "Book karun?"
      - On a yes, it books on that same call and asks for the token number.
      - "Booked" is said only when the transcript proves it.
      - Then it offers a calendar entry with reminders a day and two hours before, and directions.
      - It never pays, reschedules or cancels.
   e) DEMO HONESTY CARD
      - The hospitals, addresses and ratings on screen are real.
      - No real hospital is phoned: every hospital number is swapped for our own test phone, and a teammate
        plays the receptionist.
      - Only numbers on an allowlist can ever be dialled.

7. SKILLS THAT BECOME CODE (4:50–5:50)
   VISUAL: Ramesh's morning repeated as a stack of identical speech bubbles; they compress into a small glowing block of code labelled "sugar_diary". Then the six abilities as six code tiles grouped under the three people. The lifecycle as a circular loop that lights up stage by stage; a red cross on one tile sends the arrow back to "AI handles it". A bar chart: cab ride, AI steps "without routine 40 / with routine 25" (38% fewer).
   Idea, from our earlier project Sorted, a household-admin assistant on Telegram: the AI learns, and small
   repeated parts become tested code.
   THREE KINDS:
   - ROUTINES: after 3 successful AI runs of the same app task (like an Uber booking), a strong model
     compiles the taps into a checked routine. Over a simulated month, the learned cab routine needed 38%
     fewer AI steps, and none per ride once active.
   - RECOGNISERS: after 40 AI answers, a small Python function learns to map requests like "cab to Saket" to
     an action with no model call. It must reach 95% precision on examples it never saw.
   - PERSONAL ABILITIES: one person's own bookkeeping.
     - Ramesh: a sugar diary checked against Dr. Kapoor's targets, and a metformin countdown that says when
       Amit should reorder.
     - Sunita: a BP diary with a 7-day average, where 180/120 is always urgent, and a salt budget against the
       WHO 5 g a day.
     - Arjun: a spoken expense log, and a bill splitter ("split 2350 among me and 3 friends with 10% tip" is
       ₹647 each).
     - Nobody programmed "a diabetes feature". It came from Ramesh's mornings.
   HOW A TURN WORKS
   - Sunita: "aaj BP 146 by 92 aaya". The code stores it, grades it stage 2, and works out her weekly
     average (141 by 90).
   - The AI then replies in her style: "Sunita ji, 146 by 92 thoda high hai, Dr. Gupta ka target 130 by 80
     hai."
   - The code does the counting; the AI writes every reply. So conversation and call quality never depend on
     code.
   - Urgent results (sugar under 70, BP 180/120) are spoken exactly as the code wrote them, without waiting
     on any model.
   LIFECYCLE (animate as a loop)
   - The AI handles it, then it becomes a candidate, then it is tested on runs it never saw.
   - Family approve it, then it runs in shadow beside the AI, then it goes active.
   - The moment it fails a check, it is switched off and the AI takes over again, and fresh runs compile
     version 2.

8. THE SANDBOX ON DIGITALOCEAN (5:50–6:10)
   VISUAL: the code block passes three checkpoints (size, safe imports, fingerprint) and is sealed inside a glass box drawn on a server rack in a Bangalore data centre. Icons on the box are crossed out: network, disk, privileges. A timer reads 0.55 s; a "memory bomb" block is crushed at 0.7 s.
   - Code written by a model is untrusted. Before anything runs:
     - a 20 kB size cap;
     - a static check: only safe imports like re, json, datetime and math; no files, no network, no eval or
       exec;
     - a SHA-256 fingerprint, checked before every run.
   - Then it runs on our DigitalOcean server, in a fresh locked-down Docker container for each job: no
     network, a read-only disk, 128 MB of memory, half a CPU, no privileges. A run takes about 0.55 s, and
     anything over 10 s is killed. A test memory bomb was killed in 0.7 s.
   - Every personal ability is re-tested in this sandbox each time the backend starts.
   - Any error means the AI answers instead, as if the skill never existed.

9. CAMERA THAT KNOWS WHO IS HOLDING IT (6:10–6:35)
   VISUAL: an illustrated shop aisle. The same viewfinder frame over a generic mango-drink carton and a generic chips packet (no real brand artwork). For Ramesh: 7 teaspoon icons drop into the drink. For Sunita: a salt meter fills to 18% of a daily 5 g bar, then a bowl of roasted makhana slides in. For Arjun: only the label is read aloud as a speech bubble.
   - The live shopping camera reads the product name, the printed MRP and the expiry, and warns about
     expired packs.
   - It guides the person to the right shelf (left, right, ahead; never colours), and can hand the product
     to the Amazon cart task.
   - Ramesh holds up a Frooti: "careful, about 7 teaspoons of sugar in this pack".
   - Sunita holds up Lay's: "0.9 g of salt, 18% of your day's limit, maybe roasted makhana?"
   - Arjun holds up either: just the label.
   - Thresholds are the UK FSA front-of-pack "high" values. Code measures the finding; the AI chooses how to
     say it, once per product, kindly. A nudge, never medical advice.

10. SCAM SHIELD AND THE MORNING DIGEST (6:35–7:00)
   VISUAL: an SMS bubble containing "*401*" gets a red underline and a warning badge; an incoming call from an unknown number, then an OTP bubble flies in and is caught by a shield, with an earpiece icon sending out sound waves; a link goes into a sealed box labelled "sandbox browser" instead of the phone. Then a sunrise with a short stack of digest cards (bill, renewal, appointment, delivery) read by the waveform, and at noon a Telegram-style paper plane goes to Priya.
   SCAM SHIELD
   - Flags call-forwarding codes (*401*, and Jio's *403, *405, *409, *002), APK links and lookalike bank
     sites.
   - During a call with a stranger, if an OTP arrives, or a payment or screen-sharing app opens, a loud
     warning plays through the earpiece, with a Hang up button that really ends the call.
   - Typing a forwarding code in the dialler shows "Kya aap call forwarding on karna chahte hain?"
   - It catches the "relative in an accident, send money, tell no one" message.
   - Safety voice clips are bundled in the app, so warnings never wait on the internet.
   - Suspicious links are opened in a locked-down sandbox browser on our server, never on the phone.
   MORNING DIGEST AND WELFARE CHECK
   - Vaani reads bank, bill, renewal, appointment and delivery messages, speaks a short digest and sets
     reminders.
   - If the phone hasn't been unlocked by noon, family get a Telegram message. They also have a read-only
     family page showing the last check-in and alerts, never message contents.

11. PRIVACY, MEASURED (7:00–7:25)
   VISUAL: kinetic type, one number at a time (0 / 22, 0 / 26, 16 / 16, 10 / 10). A message bubble where the OTP and account number turn into tokens [P12], [P13] before an arrow leaves the phone; the phone keeps a small locked vault. A screen-share scammer's view goes black. Calendar pages tear off at 7 and 30 days.
   Not promised, measured by an audit script whose checks also run as tests:
   - 0 of 22 secrets (OTPs, account, card, Aadhaar and PAN numbers, UPI IDs, amounts) leave the phone.
   - 0 of 26 personal values appear in server logs, after 32 requests deliberately full of them.
   - 16 of 16 forged or cross-device requests are blocked. Only the phone that owns the data can read it.
   - 10 of 10 links are checked with their tokens and IDs stripped.
   HOW
   - OTP messages and messages from saved contacts never leave the phone at all.
   - Other private values are replaced with tokens like [P12] before anything is sent. When you ask "what
     was my OTP?", the phone itself reads the value out, so no cloud model ever hears it.
   - Hospitals hear an age band; family alerts carry no symptoms or amounts.
   - Private screens can't be screenshotted, so screen-sharing scam apps see black.
   - Messages are deleted after 30 days and task history after 7; "forget everything" deletes all memory.
   - The audit found two gaps (a UPI ID at the end of a sentence, an account number inside a link). Both
     were fixed.
   - Line: "Vaani only looks where it must."

12. COST, OPEN MODELS, SPEED (7:25–7:45)
   VISUAL: a layered diagram: "open-weight Gemma 4 31B on DigitalOcean" at the centre, Qwen as a stronger helper beside it, Gemini as a small dotted fallback. Coins that would have gone to model calls are caught by code blocks. Counters: "~55,000 tokens / person / month", "3 KB messages / month", "0 API keys needed for mock mode".
   - Most of the thinking runs on Gemma 4 31B, an open-weight model, on DigitalOcean serverless inference.
     Qwen 3.8 Max is the stronger second opinion and the skill compiler. Gemini is only a fallback.
   - All model choices live in one server file, so we can switch providers without updating the app.
   - Speech is Sarvam (Saaras listens, Bulbul speaks): 0.7–1.3 s a sentence, against 2.4–3.9 s for the
     alternative we measured.
   - What keeps costs down:
     - repeated work becomes code, with no model call;
     - simple cab requests are understood on the phone;
     - screenshots are taken only when needed, and compressed;
     - an on-phone classifier takes over when the network is down;
     - memory writes happen in the background;
     - every call is metered per device.
   - About 55,000 model tokens per person per month (estimated), and about 3 KB of message traffic a month.
   - Every feature also runs with no API keys in mock mode, so the whole product can be tested for free.
   - About 300 backend tests and about 160 Android tests guard the safety rules.

13. CLOSE (7:45–8:00)
   VISUAL: back to the opening illustration; the same hands now just speak, and a stylised cab pulls up outside. The three characters together in one frame. The Vaani wordmark (वाणी) and the waveform.
   - Back to the grandparent, booking a cab by voice.
   - Line: "Your codes never leave your phone. Nothing happens until you say haan. Vaani."
   - Logo, then the team: Aryan, Keshav Goel, Anish Manchanda.

DO NOT CLAIM
- That real hospitals were phoned, or that the ambulance is a real 108 service. It is our dispatch API
  stand-in.
- That the three people's abilities were compiled live from their voices. They are seeded to show the result
  of the learning loop, and they are tested for real at every start.
- Real brand logos, app screens or packaging (Uber, Pronto, Google, Frooti, Lay's, banks). Name them in the
  narration if needed, but draw generic versions.
- Any money saved in rupees or dollars. No prices are configured; only token counts are measured.
- That Vaani pays, cancels or acts in banking or UPI apps. It never does.
- That everything stays on the phone. During a task, screen text and screenshots from allowed apps go to a
  cloud model; they are never logged, and the task stops at secure screens.
- That every model is open-weight. Gemma is; do not call Qwen 3.8 Max or Gemini open-weight.
- That the conversation is realtime streaming. Voice is turn-based today; only the camera streams live.
```

---

## 2. Fact sheet (for checking the script)

**Product.**
- An Android app (Kotlin, Compose; Android 10 and up) and a FastAPI backend.
- The backend runs on a DigitalOcean droplet: Bangalore region, 4 vCPU, 8 GB, HTTPS through Caddy, firewall open
  only on 22, 80 and 443.
- Voice is turn-based, through Sarvam Saaras and Bulbul. The realtime voice relay is built but switched off
  (`LIVE_VOICE = false`). The shopping camera uses Gemini Live.

**The three demo users** (`fixtures/personas/personas.json`):

| Person | Represents | Language | Abilities | Camera |
|---|---|---|---|---|
| Ramesh, 74 | An older person with a chronic condition, routine and family nearby | Hindi | Sugar diary, metformin countdown | Sugar warning on Frooti |
| Sunita, 67 | An independent person managing a condition through diet | Hinglish | BP diary, salt budget | Salt warning on chips |
| Arjun, 41 | A younger blind person who needs everything spoken | English | Spoken expense log, bill splitter | None (no diet condition) |

- Their facts, appointments and ability history live in their own Meko datapacks (`saathi-persona-<id>`).
- They are keyed by plain ids, not devices, so the same three appear on every team laptop.
- `POST /abilities/<id>/reset` restores the seeded state before a demo.
- The month-long simulation also has a fourth profile, Kamla Devi (wheelchair, hard of hearing), who gets an XL
  cab. She has no abilities.

**App case studies** ([APP_CASE_STUDIES.md](../features/APP_CASE_STUDIES.md)):

| | Uber | Pronto |
|---|---|---|
| How Vaani sees | Accessibility text (up to 150 elements) | Text plus a JPEG every step (≤ 1080 px, quality 70) |
| How it taps | By element | By element, or by screen coordinates where the tree is blind |
| How it starts | Deep link built by Vaani's code, with the destination set | Opens the app and taps through |
| Where it stops | Asks yes before Book | Asks yes before Add to cart, stops at the cart |
| Can it become a routine? | Yes | Only the steps reachable by text |

Recorded fixture screens: Uber, Pronto, Amazon, Flipkart, Practo, gallery share, Settings font size and dark mode.

**Doctor feature** ([HEALTHCARE_KT.md](../features/HEALTHCARE_KT.md), [CARE.md](../features/CARE.md)):
- 16 red flags; at most 3 intake questions; a 15 s countdown.
- Ambulance statuses: received, assigned, en route, at scene, patient on board, to hospital, completed.
- Search widens 8 → 15 → 25 km. Weights:

| Case | Capability | Nearness | Reputation |
|---|---|---|---|
| Urgent | 0.40 | 0.45 | 0.15 |
| Normal | 0.45 | 0.35 | 0.20 |
| Routine | 0.50 | 0.25 | 0.25 |

- +0.15 for a hospital the person asked for.
- 3 parallel calls and 2 backups.
- After the calls, hospitals are ordered by: specialist confirmed, meets the needs, earliest slot, travel time, fee,
  rating.
- Number masking: `MASK_HOSPITAL_NUMBERS=1` and `CALL_ALLOWED_NUMBERS`.

**Compiled code** ([COMPILED_CODE.md](../features/COMPILED_CODE.md)):
- **Routines:** 3 runs to compile; 30% held out, of which 90% must replay; 3 agreeing shadow runs; degraded on 2
  misses in the last 5; a canary check on 1 step in 20.
- **Recognisers:** 40 samples; 95% precision; 30% coverage; 20 shadow agreements.
- **Sandbox:** Docker with `--network none --read-only --memory 128m --cpus 0.5 --pids-limit 32 --cap-drop ALL`.
  Runs take 0.52–0.61 s on the droplet.

**Memory** ([MEKO_STATE.md](../features/MEKO_STATE.md)):
- Seven agents: voice, care, tasks, inbox, camera, abilities, family.
- One datapack per person.
- The live check found 11 memories for Ramesh, 10 for Sunita and 10 for Arjun.

**Privacy** ([PRIVACY_AUDIT.md](../privacy/PRIVACY_AUDIT.md), [PRIVACY_PITCH.md](../privacy/PRIVACY_PITCH.md)):
- The four headline numbers in the prompt.
- How long data is kept: the care session 6 h; call transcripts until the booking is done; task steps 7 days;
  messages and the private-values vault 30 days.
- Still open, say so if asked:
  - screen text during tasks goes to a cloud model;
  - commit buttons are recognised by their wording;
  - the family link uses a long-lived key;
  - coordinate taps (Pronto) are not bounds-checked;
  - the yes gate for them relies on a labelled element being under the point.

**Cost** ([SIMULATION.md](../evaluation/SIMULATION.md), [LEARNING.md](../features/LEARNING.md)):
- About 55,568 tokens per person per month (estimated).
- The cab routine cuts AI steps by 38%.
- Message traffic is about 3.1 KB a month.
- Primary model calls take 2.7–6 s.
- No rupee or dollar figure: `pricing` in `models.yaml` is deliberately empty.

**Tests:**
- 174 backend test functions (about 300 cases with parameters);
- 163 Android unit tests;
- the privacy audit's checks also run as tests.

**Origin:** Sorted (`../sorted`) learns household-admin skills over Telegram with the same loop. Vaani brings it to a
voice assistant, and adds:
- a real Docker sandbox;
- shadow testing for routines that act on screens;
- rolling health checks;
- a code fingerprint checked before every run.

---

## 3. Generated visuals: style guide and ready-to-use prompts

The video uses **no screenshots or recordings of the app**. Everything on screen is generated: illustrations,
motion graphics and kinetic type. That keeps the focus on what Vaani does, and it means nothing goes stale when the
UI changes.

### Style guide (give this to every tool and every person making visuals)

| Element | Rule |
|---|---|
| Palette | White `#FFFFFF`, ink black `#111111`, one teal accent `#0F766E`. Red `#DC2626` only for emergencies and scams, amber `#D97706` only for warnings |
| Type | One clean sans-serif (Inter or similar), plus Noto Sans Devanagari for Hindi. Big: numbers at least 120 px at 1080p |
| Illustration | Flat 2D, soft paper grain, warm light, Indian homes and streets. No 3D renders, no stock-photo look |
| Characters | Ramesh (74, kurta, reading glasses, chai), Sunita (67, salwar suit, walking shoes, BP cuff), Arjun (41, dark glasses, white cane, earphones). Keep them the same in every shot |
| Vaani | A soft teal waveform or ring. Never a robot, face or phone screen |
| Apps | Generic outlines with labelled boxes. No real logos, screens or pack artwork |
| Motion | Elements build in step by step with the narration. Ease in and out, no bounces, nothing faster than 300 ms |
| Format | 16:9 at 1920×1080 for the main video; 9:16 at 1080×1920 for the teaser |

### Image or video generator prompts (Midjourney, Ideogram, DALL·E, Runway, Pika, Kling, Veo)

Add the style line to each prompt: *"flat 2D illustration, soft paper grain, warm light, white background, black
line work, single teal accent #0F766E, no text, no logos, 16:9"*.

1. **Ramesh's morning:** a 74-year-old Indian man in a kurta sits at a small table in a Delhi flat at dawn, a cup
   of chai and a glucometer beside him, speaking toward a soft teal sound wave floating in the air.
2. **Sunita after her walk:** a 67-year-old Indian woman in a salwar suit and walking shoes on a bench in a
   Gurugram park at dusk, a blood-pressure cuff on her arm, a small jar of achaar in her tote bag.
3. **Arjun at home:** a 41-year-old blind Indian man with dark glasses and earphones, a white cane resting
   against a sofa, listening calmly, with a teal sound wave near his ear.
4. **The scam call:** an older woman's hands holding a phone with an unknown incoming call, a red speech bubble
   asking for an OTP, a teal shield appearing between the phone and the bubble.
5. **The hospital call:** a hospital reception desk in India, a receptionist on a landline, a teal sound wave
   coming from the receiver, a calendar and a token slip on the counter.
6. **The ambulance:** a top-down stylised city map with roads as black lines, a small white ambulance moving
   along a teal route toward a house pin.
7. **The shop aisle:** an Indian kirana shop aisle with shelves of generic drinks and snack packets (no brand
   names), a square camera viewfinder frame hovering over one packet.
8. **The sandbox:** a small glowing block of code inside a sealed glass cube on a server rack, with network and
   disk icons crossed out around it.
9. **One memory:** a glowing folder with a person's silhouette on it, seven thin labelled streams of light
   flowing into it from small icons (microphone, stethoscope, cab, envelope, camera, code, family).
10. **The close:** the same older woman from shot 4, now smiling and speaking to her phone, a stylised cab
    pulling up outside her gate.

### Motion graphics to build (After Effects, Keynote, Figma, Canva, Jitter or Rive)

Each one comes straight from a doc; the doc has the exact steps and numbers.

| Graphic | Source | What it shows |
|---|---|---|
| The Vaani loop | APP_CASE_STUDIES.md Part 0 | Hear → Launch → Look → Think → Check → Ask → Act, with who decides each step |
| Uber vs Pronto | APP_CASE_STUDIES.md §2.7 | Text list vs text plus picture with a crosshair tap |
| The safety gate | PRIVACY_PITCH.md §3 | An AI suggestion meets the code gate; "Book" becomes "haan?" |
| Seven agents, one memory | MEKO_STATE.md §2 | Seven streams into one person's folder, then the appointment card reused by the cab |
| Emergency vs appointment | HEALTHCARE_KT.md §2 | The two-path flow, the countdown and the ambulance map |
| Find, filter, score, call | HEALTHCARE_KT.md §4 | Pins in widening rings, filtered pins, score bars, three calls at once, re-ranking |
| Score weights | HEALTHCARE_KT.md §4.2 | Three stacked bars: urgent, normal, routine |
| Skill lifecycle | COMPILED_CODE.md §3 | The loop from AI to candidate, tested, approved, shadow, active and degraded |
| Sandbox layers | COMPILED_CODE.md §4 | Size cap, static check, fingerprint, sealed container, time and output limits |
| Sugar and salt meters | PERSONAL_ABILITIES.md §4 | Teaspoons dropping into a drink; a salt bar filling to 18% of 5 g |
| Tokens instead of secrets | PRIVACY_AUDIT.md §1 | An SMS whose OTP and account number turn into [P12] tokens before leaving |
| Privacy scoreboard | PRIVACY_AUDIT.md headline | 0/22, 0/26, 16/16, 10/10 |
| Cost and models | LEARNING.md "Models", SIMULATION.md | Gemma at the centre, the 38% bar, the monthly token counter |

### Sound

- Calm acoustic or lo-fi bed, dropping out for the emergency scene (siren and countdown only) and the scam scene.
- App lines (Hindi or Hinglish) voiced by a warm female voice, matching Vaani's own voice, with English subtitles.
- A soft chime when the teal gate checks something; a low tone when a red warning appears.

**Never show:** API keys, the `.env` file, server addresses, real phone numbers, real messages, or real brand logos
and packaging.
