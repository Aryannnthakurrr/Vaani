"""Print demo SMS as `sender<TAB>text` lines for seed_inbox.sh.

Emulator SMS senders must be numeric, so alphanumeric headers (VM-BSESDL) map to stable fake numbers.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent.parent / "fixtures/messages/demo_sms.json"
sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
for msg in json.loads(path.read_text("utf-8")):
    sender = msg["sender"]
    if not re.fullmatch(r"\+?\d{5,15}", sender):
        sender = "5" + str(int(hashlib.sha1(sender.encode()).hexdigest(), 16) % 10**8).zfill(8)
    print(f"{sender}\t{msg['text']}")
