"""TwiML builders and Twilio request signing. Pure functions, unit-tested."""

from __future__ import annotations

import base64
import hashlib
import hmac
from collections.abc import Mapping
from xml.sax.saxutils import escape, quoteattr

# Polly.Aditi speaks Indian English and Hindi, which matches how a Delhi clinic receptionist talks.
VOICE = "Polly.Aditi"


def _say(text: str, language: str) -> str:
    return f'<Say voice="{VOICE}" language="{language}">{escape(text)}</Say>'


def say_and_listen(text: str, action_url: str, *, language: str = "en-IN", hints: str = "") -> str:
    """Speak one line, then listen for the clinic's spoken reply and POST it to `action_url`."""
    hint_attr = f" hints={quoteattr(hints)}" if hints else ""
    return (
        '<?xml version="1.0" encoding="UTF-8"?><Response>'
        f'<Gather input="speech" language="{language}" speechTimeout="auto" timeout="7" '
        f'actionOnEmptyResult="true" action={quoteattr(action_url)} method="POST"{hint_attr}>'
        f"{_say(text, language)}</Gather></Response>"
    )


def say_and_hang_up(text: str, *, language: str = "en-IN") -> str:
    return f'<?xml version="1.0" encoding="UTF-8"?><Response>{_say(text, language)}<Hangup/></Response>'


def signature(auth_token: str, url: str, params: Mapping[str, str]) -> str:
    """Twilio's X-Twilio-Signature: base64(HMAC-SHA1(token, url + sorted key/value pairs))."""
    payload = url + "".join(k + params[k] for k in sorted(params))
    digest = hmac.new(auth_token.encode(), payload.encode(), hashlib.sha1).digest()
    return base64.b64encode(digest).decode()


def signature_valid(auth_token: str, url: str, params: Mapping[str, str], header: str | None) -> bool:
    if not auth_token or not header:
        return False
    return hmac.compare_digest(signature(auth_token, url, params), header)
