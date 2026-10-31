"""Cheap, deterministic URL checks: APK links, IP hosts, punycode, brand lookalikes, shorteners."""

from __future__ import annotations

import hashlib
import ipaddress
import re
import unicodedata
from dataclasses import dataclass, field
from urllib.parse import urlsplit, urlunsplit

# Brand → official registered domains. Hosts using a brand token outside these are impersonation.
BRANDS: dict[str, tuple[str, ...]] = {
    "sbi": ("sbi.co.in", "onlinesbi.sbi", "sbi.bank.in", "sbicard.com"),
    "hdfc": ("hdfcbank.com", "hdfc.bank.in", "hdfclife.com"),
    "icici": ("icicibank.com", "icici.bank.in"),
    "axis": ("axisbank.com", "axis.bank.in"),
    "paytm": ("paytm.com", "paytmbank.com"),
    "phonepe": ("phonepe.com",),
    "amazon": ("amazon.in", "amazon.com", "amazon.co.in"),
    "flipkart": ("flipkart.com",),
    "irctc": ("irctc.co.in",),
    "uidai": ("uidai.gov.in",),
    "incometax": ("incometax.gov.in",),
    "indiapost": ("indiapost.gov.in",),
    "bluedart": ("bluedart.com",),
    "delhivery": ("delhivery.com",),
    "bses": ("bsesdelhi.com",),
    "tatapower": ("tatapower.com", "tatapower-ddl.com"),
    "bescom": ("bescom.co.in",),
    "msedcl": ("mahadiscom.in",),
    "uppcl": ("uppcl.org",),
}
SHORTENERS = frozenset(
    "bit.ly tinyurl.com t.co goo.gl is.gd cutt.ly rb.gy ow.ly shorturl.at t.ly rebrand.ly tiny.cc "
    "s.id v.gd bitly.ws".split()
)
MULTI_PART_SUFFIXES = ("co.in", "gov.in", "org.in", "net.in", "ac.in", "bank.in", "co.uk", "com.au")
# Visually confusable characters folded to their ASCII lookalike before comparing to brands.
HOMOGLYPHS = str.maketrans(
    {
        "0": "o",
        "1": "l",
        "3": "e",
        "5": "s",
        "7": "t",
        "@": "a",
        "$": "s",
        "|": "l",
        "а": "a",
        "е": "e",
        "о": "o",
        "р": "p",
        "с": "c",
        "х": "x",
        "і": "i",
        "ӏ": "l",
        "ѕ": "s",
    }
)

_URL_RE = re.compile(
    r"(?:https?://|www\.)\S+|\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|info|xyz|top|ly|co|"
    r"me|io|app|site|online|live|shop|club|link|apk)(?:/\S*)?",
    re.IGNORECASE,
)


@dataclass
class UrlFacts:
    url: str
    host: str
    registered_domain: str
    signals: list[str] = field(default_factory=list)
    brand: str | None = None
    official: bool = False
    shortener: bool = False

    @property
    def dangerous(self) -> bool:
        return any(s.startswith(("apk_link", "brand_lookalike", "brand_impersonation")) for s in self.signals)

    @property
    def suspicious(self) -> bool:
        return bool(self.signals) and not self.dangerous


def extract_urls(text: str) -> list[str]:
    return [m.group(0).rstrip(".,);!?'\"") for m in _URL_RE.finditer(text or "")]


def normalize_url(raw: str) -> str:
    raw = raw.strip().strip("<>\"'")
    if not re.match(r"^[a-z][a-z0-9+.-]*://", raw, re.IGNORECASE):
        raw = "http://" + raw
    parts = urlsplit(raw)
    host = (parts.hostname or "").rstrip(".").lower()
    try:
        host = host.encode("idna").decode("ascii") if any(ord(c) > 127 for c in host) else host
    except UnicodeError:
        pass
    netloc = host + (f":{parts.port}" if parts.port else "")
    return urlunsplit((parts.scheme.lower(), netloc, parts.path or "/", parts.query, ""))


def url_hash(url: str) -> str:
    return hashlib.sha256(normalize_url(url).encode()).hexdigest()


def registered_domain(host: str) -> str:
    labels = host.split(".")
    for suffix in MULTI_PART_SUFFIXES:
        if host.endswith("." + suffix):
            return ".".join(labels[-(suffix.count(".") + 2) :])
    return ".".join(labels[-2:])


def is_ip_host(host: str) -> bool:
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return False


def is_apk_url(url: str) -> bool:
    path = urlsplit(normalize_url(url)).path.lower()
    query = urlsplit(normalize_url(url)).query.lower()
    return path.endswith(".apk") or ".apk" in query or "/apk" in path or "apk/" in path or "/download" in path


def fold(label: str) -> str:
    try:
        label = label.encode("ascii").decode("idna") if label.startswith("xn--") else label
    except UnicodeError:
        pass
    label = unicodedata.normalize("NFKC", label.lower()).translate(HOMOGLYPHS)
    label = label.replace("rn", "m").replace("vv", "w")
    return "".join(c for c in label if c.isalnum())


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _max_distance(brand: str) -> int:
    return 0 if len(brand) <= 3 else 1 if len(brand) <= 5 else 2


def brand_match(host: str) -> tuple[str | None, str]:
    """Return (brand, kind) where kind is 'official', 'impersonation', 'lookalike' or ''."""
    reg = registered_domain(host)
    for brand, domains in BRANDS.items():
        if reg in domains or host in domains:
            return brand, "official"
    labels = host.split(".")[:-1] or [host]
    tokens = [t for label in labels for t in re.split(r"[-_]", label) if t]
    for brand in BRANDS:
        for raw in [*tokens, *labels]:
            folded = fold(raw)
            low = raw.lower()
            if low == brand or (
                brand in low if len(brand) > 3 else low.startswith(brand) or low.endswith(brand)
            ):
                return brand, "impersonation"
            if folded == brand or (len(brand) > 3 and brand in folded):
                return brand, "lookalike"
            if len(folded) >= 4 and 0 < levenshtein(folded, brand) <= _max_distance(brand):
                return brand, "lookalike"
    return None, ""


def analyse(url: str) -> UrlFacts:
    norm = normalize_url(url)
    host = urlsplit(norm).hostname or ""
    facts = UrlFacts(url=norm, host=host, registered_domain=registered_domain(host))
    facts.shortener = facts.registered_domain in SHORTENERS or host in SHORTENERS
    if is_apk_url(norm):
        facts.signals.append("apk_link: the link downloads an app file")
    if is_ip_host(host):
        facts.signals.append("ip_host: the link uses a bare IP address")
    if any(label.startswith("xn--") for label in host.split(".")):
        facts.signals.append("punycode: the address uses look-alike foreign letters")
    brand, kind = brand_match(host)
    facts.brand = brand
    if kind == "official":
        facts.official = True
    elif kind == "impersonation":
        facts.signals.append(f"brand_impersonation: uses the name '{brand}' but is not its real website")
    elif kind == "lookalike":
        facts.signals.append(f"brand_lookalike: spelled to look like '{brand}'")
    if facts.shortener:
        facts.signals.append("shortener: the real address is hidden")
    # SMS links are usually written without a scheme ("airtel.in/pay"); only an explicit http:// counts.
    explicit_http = url.strip().strip("<>\"'").lower().startswith("http://")
    if explicit_http and not facts.official and not facts.shortener:
        facts.signals.append("no_https: the link is not secure")
    return facts


def rule_verdict(facts: UrlFacts, extra_signals: list[str] | None = None) -> tuple[str, str, str]:
    """Deterministic verdict used by mock mode and as the floor for the LLM verdict."""
    signals = [*facts.signals, *(extra_signals or [])]
    if any(s.startswith("apk_link") for s in signals):
        return (
            "dangerous",
            "This link installs an app that can take control of your phone. Do not open it.",
            "यह लिंक एक ऐप डाउनलोड करता है जो आपका फ़ोन कब्ज़े में ले सकता है। इसे मत खोलिए।",
        )
    if any(
        s.startswith(("brand_lookalike", "brand_impersonation", "safe_browsing", "asks_secrets"))
        for s in signals
    ):
        name = facts.brand.upper() if facts.brand else "a company"
        return (
            "dangerous",
            f"This link pretends to be {name} but it is a fake website. Do not open it.",
            f"यह लिंक {name} होने का नाटक करता है, पर यह नकली वेबसाइट है। इसे मत खोलिए।",
        )
    risky = [s for s in signals if not s.startswith(("no_https", "shortener"))]
    if risky:
        return (
            "suspicious",
            "This link looks unusual. Please ask your family before opening it.",
            "यह लिंक अजीब लग रहा है। खोलने से पहले परिवार से पूछ लीजिए।",
        )
    if facts.official:
        return (
            "safe",
            f"This is the real {facts.brand.upper() if facts.brand else ''} website.".replace("  ", " "),
            "यह असली वेबसाइट है।",
        )
    if facts.shortener:
        return (
            "suspicious",
            "This link hides where it goes. Please ask your family before opening it.",
            "यह लिंक छुपाता है कि कहाँ जाता है। खोलने से पहले परिवार से पूछ लीजिए।",
        )
    if any(s.startswith("no_https") for s in signals):
        return (
            "suspicious",
            "This link is not secure. Please ask your family before opening it.",
            "यह लिंक सुरक्षित नहीं है। खोलने से पहले परिवार से पूछ लीजिए।",
        )
    return ("safe", "I did not find anything wrong with this link.", "मुझे इस लिंक में कोई खतरा नहीं मिला।")
