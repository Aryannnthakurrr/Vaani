"""Generate screen fixtures and matching mock agent scripts.

Screens and scripts are written together so node IDs in the scripts always match the
serializer's numbering (list order, starting at 1). Run from the repo root:

    python scripts/gen_fixtures.py
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCREENS = ROOT / "fixtures" / "screens"
SCRIPTS = ROOT / "fixtures" / "agent_scripts"

W = 1080  # screen width used for all fixtures


def node(cls, text=None, *, desc=None, hint=None, view_id=None, top=0, h=120, left=40, right=W - 40,
         depth=1, clickable=False, editable=False, scrollable=False, checkable=False, checked=False,
         focused=False):
    return {
        "cls": cls, "text": text, "desc": desc, "hint": hint, "viewId": view_id,
        "bounds": [left, top, right, top + h], "clickable": clickable, "editable": editable,
        "scrollable": scrollable, "checkable": checkable, "checked": checked, "focused": focused,
        "depth": depth,
    }


def btn(text, top, **kw):
    kw.setdefault("clickable", True)
    return node(kw.pop("cls", "Button"), text, top=top, **kw)


def label(text, top, **kw):
    return node("TextView", text, top=top, **kw)


def screen(package, title, nodes, secure=False):
    return {"package": package, "title": title, "secure": secure, "nodes": nodes}


def nid(scr, needle):
    """1-based id of the first node whose text/desc/hint equals or contains needle."""
    for i, n in enumerate(scr["nodes"], start=1):
        for field in ("text", "desc", "hint"):
            if n[field] and (n[field] == needle or needle in n[field]):
                return i
    raise KeyError(f"{needle!r} not on screen {scr['title']!r}")


def list_rows(labels, start=360, step=150, depth=2, **kw):
    return [btn(t, start + i * step, cls="TextView", depth=depth, **kw) for i, t in enumerate(labels)]


# --------------------------------------------------------------------------- settings
SETTINGS = "com.android.settings"
settings_home = screen(SETTINGS, "Settings", [
    label("Settings", 120, depth=0),
    node("EditText", hint="Search settings", top=240, depth=1, clickable=True, editable=True),
    node("RecyclerView", top=340, h=2000, depth=1, scrollable=True),
    *list_rows(["Network & internet", "Connected devices", "Apps", "Notifications", "Battery",
                "Display", "Sound & vibration", "Accessibility"]),
])
display = screen(SETTINGS, "Display", [
    node("ImageButton", desc="Navigate up", top=100, left=20, right=140, depth=1, clickable=True),
    label("Display", 120, left=180, depth=1),
    node("RecyclerView", top=300, h=2000, depth=1, scrollable=True),
    *list_rows(["Brightness level", "Adaptive brightness"]),
    node("Switch", "Dark theme", top=660, depth=2, clickable=True, checkable=True, checked=False),
    *list_rows(["Font size", "Screen timeout", "Auto-rotate screen"], start=810),
])
display_dark = json.loads(json.dumps(display))
display_dark["nodes"][nid(display, "Dark theme") - 1]["checked"] = True
font_size = screen(SETTINGS, "Font size", [
    node("ImageButton", desc="Navigate up", top=100, left=20, right=140, depth=1, clickable=True),
    label("Font size", 120, left=180, depth=1),
    label("Preview", 400, depth=1),
    label("Make the text on your screen smaller or larger.", 520, depth=1),
    node("SeekBar", desc="Font size", top=1500, depth=1, clickable=True, scrollable=True),
    label("Default", 1640, depth=1),
])
font_size_big = json.loads(json.dumps(font_size))
font_size_big["nodes"][nid(font_size, "Default") - 1]["text"] = "Largest"


def act(action, reason, **fields):
    return {"action": action, **fields, "reason": reason}


def step(when, action):
    return {"when_contains": when, "action": action}


SCENARIOS: dict[str, dict] = {}

SCENARIOS["settings_font_size"] = {
    "mode": "GUIDE",
    "goal_keywords": ["font", "text size", "bigger text", "akshar", "अक्षर", "text bada"],
    "screens": [settings_home, display, font_size, font_size_big],
    "steps": [
        step(["Network & internet", "Display"], act("highlight", "Display opens font settings",
             id=nid(settings_home, "Display"), instruction="Tap Display. It is in the list, below Battery.")),
        step(["Dark theme", "Font size"], act("highlight", "Font size row is visible",
             id=nid(display, "Font size"), instruction="Now tap Font size.")),
        step(["Preview", "Default"], act("highlight", "Slider controls the size",
             id=nid(font_size, "SeekBar") if False else 5,
             instruction="Drag the round button on the line to the right. The text will get bigger.")),
        step(["Largest"], act("done", "Font is now largest",
             message="Well done! Your text is bigger now.")),
    ],
}

SCENARIOS["settings_dark_mode"] = {
    "mode": "ACT",
    "goal_keywords": ["dark mode", "dark theme", "andhera", "dark"],
    "screens": [settings_home, display, display_dark],
    "steps": [
        step(["Network & internet", "Display"], act("tap", "Dark theme lives under Display",
             id=nid(settings_home, "Display"))),
        step(["Dark theme"], act("tap", "Toggle the dark theme switch", id=nid(display, "Dark theme"))),
        step(["Dark theme"], act("done", "Switch is now checked", message="Dark mode is on now.")),
    ],
}

# --------------------------------------------------------------------------- uber
UBER = "com.ubercab"
uber_home = screen(UBER, "Uber", [
    node("Button", "Where to?", top=820, depth=1, clickable=True),
    btn("Ride", 1000, left=40, right=330, depth=2),
    btn("Package", 1000, left=380, right=670, depth=2),
    btn("Reserve", 1000, left=720, right=1040, depth=2),
    label("Suggestions", 1200, depth=1),
])
uber_search = screen(UBER, "Plan your ride", [
    node("ImageButton", desc="Back", top=100, left=20, right=140, depth=1, clickable=True),
    node("EditText", "Current location", hint="Pickup location", top=220, depth=1, clickable=True,
         editable=True),
    node("EditText", hint="Where to?", top=360, depth=1, clickable=True, editable=True, focused=True),
    label("Saved places", 520, depth=1),
])
uber_results = screen(UBER, "Plan your ride", [
    node("ImageButton", desc="Back", top=100, left=20, right=140, depth=1, clickable=True),
    node("EditText", "Apollo Hospital", hint="Where to?", top=360, depth=1, clickable=True,
         editable=True, focused=True),
    btn("Apollo Hospital, Sarita Vihar, Mathura Road, New Delhi", 520, cls="TextView", depth=2),
    btn("Apollo Hospital, Sector 26, Noida", 680, cls="TextView", depth=2),
    btn("Apollo Pharmacy, Lajpat Nagar", 840, cls="TextView", depth=2),
])
uber_options = screen(UBER, "Choose a ride", [
    node("ImageButton", desc="Back", top=100, left=20, right=140, depth=1, clickable=True),
    btn("Uber Go  ₹184  4 min away", 1300, cls="TextView", depth=2, checkable=True, checked=True),
    btn("Premier  ₹236  6 min away", 1450, cls="TextView", depth=2, checkable=True),
    btn("Auto  ₹96  3 min away", 1600, cls="TextView", depth=2, checkable=True),
    label("Cash", 1760, depth=1),
    node("Button", "Confirm Uber Go", top=1900, depth=1, clickable=True),
])
uber_assigned = screen(UBER, "Your ride", [
    label("Ramesh is arriving in 4 min", 1200, depth=1),
    label("White Maruti Swift Dzire · DL 3C AB 1234", 1330, depth=1),
    label("4.9 ★", 1440, depth=1),
    btn("Call", 1600, left=40, right=520, depth=1),
    btn("Cancel ride", 1600, left=560, right=1040, depth=1),
])
SCENARIOS["uber_book"] = {
    "mode": "ACT",
    "goal_keywords": ["cab", "uber", "taxi", "ride", "कैब", "गाड़ी", "gaadi"],
    "screens": [uber_home, uber_search, uber_results, uber_options, uber_assigned],
    "steps": [
        step(["Where to?", "Ride"], act("tap", "Open destination search", id=nid(uber_home, "Where to?"))),
        step(["Pickup location"], act("type", "Type the destination",
             id=nid(uber_search, "Where to?"), text="Apollo Hospital")),
        step(["Sarita Vihar"], act("tap", "Top result matches the goal",
             id=nid(uber_results, "Sarita Vihar"))),
        step(["Confirm Uber Go"], act("confirm", "Booking needs a spoken yes",
             summary="Uber Go to Apollo Hospital, Sarita Vihar. Fare ₹184, the car is 4 minutes away. Book it?",
             then={"action": "tap", "id": nid(uber_options, "Confirm Uber Go"),
                   "reason": "User approved"})),
        step(["arriving"], act("done", "Driver assigned",
             message="Your cab is booked. Ramesh is coming in a white Swift Dzire, 4 minutes away.")),
    ],
}

# --------------------------------------------------------------------------- amazon
AMAZON = "in.amazon.mShop.android.shopping"
amz_home = screen(AMAZON, "Amazon.in", [
    node("EditText", hint="Search Amazon.in", top=160, depth=1, clickable=True, editable=True),
    btn("Fresh", 320, left=40, right=330, depth=2),
    btn("Mobiles", 320, left=380, right=670, depth=2),
    label("Deals for you", 520, depth=1),
])
amz_search = screen(AMAZON, "Amazon.in", [
    node("EditText", hint="Search Amazon.in", top=160, depth=1, clickable=True, editable=True,
         focused=True),
])
amz_results = screen(AMAZON, "Results", [
    node("EditText", "Parle-G biscuits", hint="Search Amazon.in", top=160, depth=1, clickable=True,
         editable=True),
    btn("Sponsored · Sunfeast Marie Light 1 kg ₹120", 400, cls="TextView", depth=2),
    btn("Parle-G Gold Biscuits, 1 kg · ₹140 · 4.3 out of 5 stars", 700, cls="TextView", depth=2),
    btn("Parle-G Original Glucose Biscuits 800 g · ₹90 · 4.4 out of 5 stars", 1000, cls="TextView",
        depth=2),
])
amz_product = screen(AMAZON, "Product", [
    label("Parle-G Gold Biscuits, 1 kg", 300, depth=1),
    label("4.3 out of 5 stars · 12,431 ratings", 420, depth=1),
    label("₹140", 900, depth=1),
    node("Button", "Add to Cart", top=1500, depth=1, clickable=True),
    node("Button", "Buy Now", top=1650, depth=1, clickable=True),
])
amz_added = screen(AMAZON, "Cart", [
    label("Added to Cart", 300, depth=1),
    node("Button", "Proceed to Buy (1 item)", top=500, depth=1, clickable=True),
    node("Button", "Go to Cart", top=650, depth=1, clickable=True),
])
SCENARIOS["amazon_add_cart"] = {
    "mode": "ACT",
    "goal_keywords": ["amazon", "cart", "order", "buy", "kharid", "मंगा", "manga"],
    "screens": [amz_home, amz_search, amz_results, amz_product, amz_added],
    "steps": [
        step(["Search Amazon.in", "Deals"], act("tap", "Open search", id=nid(amz_home, "Search Amazon.in"))),
        step(["Search Amazon.in"], act("type", "Search for the product",
             id=nid(amz_search, "Search Amazon.in"), text="Parle-G biscuits")),
        step(["Parle-G Gold"], act("tap", "Top non-sponsored match",
             id=nid(amz_results, "Parle-G Gold Biscuits"))),
        step(["Add to Cart"], act("confirm", "Adding to cart needs a yes",
             summary="Parle-G Gold Biscuits, 1 kg. Price ₹140, rated 4.3 out of 5. Add it to your cart?",
             then={"action": "tap", "id": nid(amz_product, "Add to Cart"), "reason": "User approved"})),
        step(["Added to Cart"], act("done", "Item is in the cart; never check out",
             message="Done. Parle-G Gold is in your Amazon cart. I have not placed the order.")),
    ],
}

# --------------------------------------------------------------------------- flipkart
FLIPKART = "com.flipkart.android"
fk_home = screen(FLIPKART, "Flipkart", [
    node("EditText", hint="Search for products", top=180, depth=1, clickable=True, editable=True),
    btn("Grocery", 340, left=40, right=330, depth=2),
    btn("Mobiles", 340, left=380, right=670, depth=2),
    label("Top offers", 540, depth=1),
])
fk_search = screen(FLIPKART, "Flipkart", [
    node("EditText", hint="Search for products", top=180, depth=1, clickable=True, editable=True, focused=True),
])
fk_results = screen(FLIPKART, "Results", [
    node("EditText", "Parle-G biscuits", hint="Search for products", top=180, depth=1, clickable=True, editable=True),
    btn("Ad · Britannia Marie Gold 1 kg · ₹130", 420, cls="TextView", depth=2),
    btn("Parle-G Gold Biscuits 1 kg · ₹140 · 4.3 ★ (8,214)", 720, cls="TextView", depth=2),
    btn("Parle-G Original Glucose Biscuits 800 g · ₹90 · 4.4 ★", 1020, cls="TextView", depth=2),
])
fk_product = screen(FLIPKART, "Product", [
    label("Parle-G Gold Biscuits 1 kg", 300, depth=1),
    label("4.3 ★ · 8,214 ratings", 420, depth=1),
    label("₹140", 900, depth=1),
    node("Button", "Add to cart", top=2050, left=40, right=520, depth=1, clickable=True),
    node("Button", "Buy now", top=2050, left=560, right=1040, depth=1, clickable=True),
])
fk_added = screen(FLIPKART, "Cart", [
    label("Added to cart", 300, depth=1),
    node("Button", "Go to cart", top=500, depth=1, clickable=True),
])
SCENARIOS["flipkart_add_cart"] = {
    "mode": "ACT",
    "goal_keywords": ["flipkart"],
    "screens": [fk_home, fk_search, fk_results, fk_product, fk_added],
    "steps": [
        step(["Search for products", "Top offers"], act("tap", "Open search", id=nid(fk_home, "Search for products"))),
        step(["Search for products"], act("type", "Search for the product",
             id=nid(fk_search, "Search for products"), text="Parle-G biscuits")),
        step(["Parle-G Gold"], act("tap", "Top match that is not an ad",
             id=nid(fk_results, "Parle-G Gold Biscuits"))),
        step(["Add to cart"], act("confirm", "Adding to cart needs a yes",
             summary="Parle-G Gold Biscuits, 1 kg on Flipkart. Price ₹140, rated 4.3. Add it to your cart?",
             then={"action": "tap", "id": nid(fk_product, "Add to cart"), "reason": "User approved"})),
        step(["Added to cart"], act("done", "Item is in the cart; never check out",
             message="Done. Parle-G Gold is in your Flipkart cart. I have not placed the order.")),
    ],
}

# --------------------------------------------------------------------------- practo
PRACTO = "com.practo.fabric"
pr_home = screen(PRACTO, "Practo", [
    node("EditText", hint="Search for doctors, clinics, hospitals", top=200, depth=1, clickable=True,
         editable=True),
    btn("Book appointment", 420, cls="TextView", depth=2),
    btn("Consult online", 420, left=560, cls="TextView", depth=2),
])
pr_search = screen(PRACTO, "Search", [
    node("EditText", hint="Search for doctors, clinics, hospitals", top=200, depth=1, clickable=True,
         editable=True, focused=True),
    label("Noida", 340, depth=1),
])
pr_results = screen(PRACTO, "Cardiologist in Noida", [
    btn("Dr. Anil Mehta · Cardiologist · 22 years · ₹800 · 2.1 km · Next: Today 4:00 PM", 400,
        cls="TextView", depth=2),
    btn("Dr. Sunita Rao · Cardiologist · 15 years · ₹600 · 3.4 km · Next: Tomorrow 10:00 AM", 700,
        cls="TextView", depth=2),
    btn("Dr. Vikram Shah · Cardiologist · 30 years · ₹1000 · 5.0 km · Next: Today 6:30 PM", 1000,
        cls="TextView", depth=2),
])
pr_slots = screen(PRACTO, "Dr. Anil Mehta", [
    label("Mehta Heart Clinic, Sector 18, Noida", 300, depth=1),
    label("Today", 500, depth=1),
    btn("4:00 PM", 620, left=40, right=330, depth=2),
    btn("4:30 PM", 620, left=380, right=670, depth=2),
    btn("5:00 PM", 620, left=720, right=1040, depth=2),
])
pr_confirm = screen(PRACTO, "Confirm", [
    label("Dr. Anil Mehta · Mehta Heart Clinic", 300, depth=1),
    label("Today, 4:00 PM · Fee ₹800, pay at clinic", 420, depth=1),
    node("Button", "Confirm Clinic Visit", top=1800, depth=1, clickable=True),
])
pr_done = screen(PRACTO, "Appointment confirmed", [
    label("Appointment confirmed", 300, depth=1),
    label("Dr. Anil Mehta, Cardiologist", 420, depth=1),
    label("Mehta Heart Clinic, C-12 Sector 18, Noida 201301", 540, depth=1),
    label("Clinic phone: +91 98100 12345", 660, depth=1),
    label("Today, 4:00 PM · Fee ₹800", 780, depth=1),
])
SCENARIOS["practo_book"] = {
    "mode": "ACT",
    "goal_keywords": ["doctor", "practo", "appointment", "cardiolog", "डॉक्टर", "daktar"],
    "screens": [pr_home, pr_search, pr_results, pr_slots, pr_confirm, pr_done],
    "steps": [
        step(["Book appointment"], act("tap", "Open search",
             id=nid(pr_home, "Search for doctors"))),
        step(["Noida"], act("type", "Search the specialty",
             id=nid(pr_search, "Search for doctors"), text="Cardiologist")),
        step(["Dr. Anil Mehta"], act("ask_user", "User should pick a doctor",
             question="I found three heart doctors. Dr. Anil Mehta, ₹800, today 4 pm. "
                      "Dr. Sunita Rao, ₹600, tomorrow 10 am. Dr. Vikram Shah, ₹1000, today 6:30 pm. "
                      "Which one?")),
        step(["Dr. Anil Mehta"], act("tap", "User chose Dr. Mehta", id=nid(pr_results, "Anil Mehta"))),
        step(["4:00 PM"], act("tap", "Earliest slot", id=nid(pr_slots, "4:00 PM"))),
        step(["Confirm Clinic Visit"], act("confirm", "Booking needs a yes",
             summary="Dr. Anil Mehta at Mehta Heart Clinic, today 4 pm. Fee ₹800, paid at the clinic. Book it?",
             then={"action": "tap", "id": nid(pr_confirm, "Confirm Clinic Visit"),
                   "reason": "User approved"})),
        step(["Appointment confirmed"], act("done", "Booked",
             message="Your appointment with Dr. Mehta is booked for today at 4 pm.")),
    ],
}

# --------------------------------------------------------------------------- house help
# Pronto, modelled on the real app (2026-10-31): React Native, so buttons are clickable ViewGroups labelled by
# content-desc; help is booked by time (duration + slot), and "Add to cart" leads to payment, where Saathi stops.
PRONTO = "com.company.pronto"


def pr_schedule(duration_checked=False, slot_checked=False):
    return screen(PRONTO, "Schedule for later", [
        node("ViewGroup", desc="Today", top=220, left=40, right=270, depth=2, clickable=True),
        node("ViewGroup", desc="Tomorrow", top=220, left=310, right=540, depth=2, clickable=True,
             checkable=True, checked=True),
        label("All our partners are busy right now. Please schedule this booking for later.", 420, depth=1),
        label("Service duration", 680, depth=1),
        node("ViewGroup", desc="0.5 hr ₹40", top=740, left=70, right=300, depth=2, clickable=True,
             checkable=True, checked=duration_checked),
        node("ViewGroup", desc="1 hr ₹79", top=740, left=340, right=570, depth=2, clickable=True, checkable=True),
        label("Service start time", 1000, depth=1),
        node("ViewGroup", desc="Morning", top=1080, left=80, right=380, depth=2, clickable=True,
             checkable=True, checked=True),
        node("ViewGroup", desc="Afternoon", top=1080, left=400, right=680, depth=2, clickable=True, checkable=True),
        node("ViewGroup", desc="7:00 am", top=1340, left=100, right=300, depth=2, clickable=True,
             checkable=True, checked=slot_checked),
        node("ViewGroup", desc="Add to cart", top=2130, depth=1, clickable=True),
    ])


pr_home = screen(PRONTO, "Pronto", [
    label("A block, Meera Bagh", 160, depth=1),
    node("ViewGroup", desc="Get Instant Service", top=780, left=40, right=520, h=300, depth=2, clickable=True),
    node("ViewGroup", desc="Schedule for Later", top=780, left=560, right=1040, h=300, depth=2, clickable=True),
])
pr_duration = pr_schedule()
pr_slot = pr_schedule(duration_checked=True)
pr_ready = pr_schedule(duration_checked=True, slot_checked=True)
pr_cart = screen(PRONTO, "Cart", [
    label("House help · Tomorrow 7:00 am · 0.5 hr", 300, depth=1),
    label("To pay ₹40", 420, depth=1),
    node("ViewGroup", desc="Proceed to pay ₹40", top=2130, depth=1, clickable=True),
])
SCENARIOS["house_help"] = {
    "mode": "ACT",
    "goal_keywords": ["cleaning", "maid", "house help", "bai", "safai", "सफाई", "kaamwali", "pronto"],
    "screens": [pr_home, pr_duration, pr_slot, pr_ready, pr_cart],
    "steps": [
        step(["Get Instant Service"], act("tap", "No time asked, so instant",
             id=nid(pr_home, "Get Instant Service"))),
        step(["Service duration"], act("tap", "Shortest duration", id=nid(pr_duration, "0.5 hr"))),
        step(["7:00 am"], act("tap", "Earliest slot", id=nid(pr_slot, "7:00 am"))),
        step(["Add to cart"], act("confirm", "Adding to cart needs a yes",
             summary="Pronto is busy right now, so the earliest visit is tomorrow at 7 am, for half an hour, "
                     "₹40. Add it to the cart?",
             then={"action": "tap", "id": nid(pr_ready, "Add to cart"), "reason": "User approved"})),
        step(["Proceed to pay"], act("done", "In the cart; payment is the user's",
             message="House help for tomorrow at 7 am is in your Pronto cart, ₹40. "
                     "Please open Pronto and pay to confirm it.")),
    ],
}

# --------------------------------------------------------------------------- guide: share photo
PHOTOS = "com.google.android.apps.photos"
ph_view = screen(PHOTOS, "Photo", [
    node("ImageView", desc="Photo taken on 30 Oct", top=200, h=1500, depth=1),
    node("ImageButton", desc="Share", top=2050, left=40, right=240, depth=1, clickable=True),
    node("ImageButton", desc="Edit", top=2050, left=300, right=500, depth=1, clickable=True),
    node("ImageButton", desc="Favourite", top=2050, left=560, right=760, depth=1, clickable=True),
    node("ImageButton", desc="Delete", top=2050, left=840, right=1040, depth=1, clickable=True),
])
ph_share = screen("android", "Share", [
    label("Share", 1200, depth=1),
    btn("WhatsApp", 1400, left=40, right=270, depth=2),
    btn("Gmail", 1400, left=300, right=530, depth=2),
    btn("Messages", 1400, left=560, right=790, depth=2),
    btn("Nearby Share", 1400, left=820, right=1040, depth=2),
])
wa = "com.whatsapp"
wa_pick = screen(wa, "Send to…", [
    node("EditText", hint="Search…", top=160, depth=1, clickable=True, editable=True),
    btn("Aarav", 400, cls="TextView", depth=2),
    btn("Beta Rohit", 550, cls="TextView", depth=2),
    btn("Family group", 700, cls="TextView", depth=2),
])
wa_selected = screen(wa, "Send to…", [
    label("Aarav", 2000, left=40, right=700, depth=1),
    node("ImageButton", desc="Send", top=1980, left=880, right=1040, depth=1, clickable=True),
])
wa_sent = screen(wa, "Aarav", [
    label("Aarav", 120, depth=1),
    node("ImageView", desc="Photo, sent", top=900, h=600, depth=2),
    node("EditText", hint="Message", top=2050, depth=1, clickable=True, editable=True),
])
SCENARIOS["gallery_share"] = {
    "mode": "GUIDE",
    "goal_keywords": ["photo", "share", "bhej", "भेज", "send", "aarav"],
    "screens": [ph_view, ph_share, wa_pick, wa_selected, wa_sent],
    "steps": [
        step(["Share", "Edit"], act("highlight", "Share is bottom-left", id=nid(ph_view, "Share"),
             instruction="Tap the Share button at the bottom left.")),
        step(["WhatsApp", "Gmail"], act("highlight", "WhatsApp in share sheet", id=nid(ph_share, "WhatsApp"),
             instruction="Tap the green WhatsApp button.")),
        step(["Aarav", "Family group"], act("highlight", "Aarav is the grandson", id=nid(wa_pick, "Aarav"),
             instruction="Tap Aarav's name.")),
        step(["Send"], act("highlight", "Send button bottom-right", id=nid(wa_selected, "Send"),
             instruction="Tap the green arrow at the bottom right to send.")),
        step(["Photo, sent"], act("done", "Photo delivered",
             message="Well done! The photo has been sent to Aarav.")),
    ],
}

# --------------------------------------------------------------------------- safety-only screens
GPAY = "com.google.android.apps.nbu.paisa.user"
safety_screens = {
    "upi_pin": screen(GPAY, "Enter UPI PIN", [
        label("Paying Sharma Kirana Store ₹540", 300, depth=1),
        label("ENTER UPI PIN", 600, depth=1),
        node("EditText", hint="UPI PIN", top=720, depth=1, editable=True, focused=True),
    ]),
    "login_password": screen(AMAZON, "Sign in", [
        label("Sign in", 200, depth=1),
        node("EditText", hint="Email or mobile number", top=400, depth=1, editable=True),
        node("EditText", hint="Password", top=560, depth=1, editable=True),
        node("Button", "Sign in", top=720, depth=1, clickable=True),
    ]),
    "secure_window": screen("com.sbi.lotusintouch", "", [], secure=True),
}


def main() -> None:
    if SCREENS.exists():
        shutil.rmtree(SCREENS)
    SCRIPTS.mkdir(parents=True, exist_ok=True)
    for old in SCRIPTS.glob("*.json"):
        old.unlink()
    for name, sc in SCENARIOS.items():
        folder = SCREENS / name
        folder.mkdir(parents=True)
        for i, scr in enumerate(sc["screens"], start=1):
            (folder / f"{i:02d}.json").write_text(json.dumps(scr, ensure_ascii=False, indent=1), "utf-8")
        script = {"scenario": name, "mode": sc["mode"], "goal_keywords": sc["goal_keywords"],
                  "steps": sc["steps"]}
        (SCRIPTS / f"{name}.json").write_text(json.dumps(script, ensure_ascii=False, indent=1), "utf-8")
    folder = SCREENS / "safety"
    folder.mkdir(parents=True)
    for name, scr in safety_screens.items():
        (folder / f"{name}.json").write_text(json.dumps(scr, ensure_ascii=False, indent=1), "utf-8")
    print(f"wrote {len(SCENARIOS)} scenarios + {len(safety_screens)} safety screens")


if __name__ == "__main__":
    main()
