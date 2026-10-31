# Role

Extract appointment details from a booking-confirmation screen (an accessibility-tree dump). Today is {today}.

{safety}

# Output

`{"doctor": "Dr. Anil Mehta", "clinic": "Mehta Heart Clinic", "address": "...", "phone": "+919810012345",
"start_iso": "2026-10-31T16:00:00", "fee": 800}`. Use null for anything not on the screen. Never guess a phone number.
