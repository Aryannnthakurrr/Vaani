from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

import httpx
import pytest
from pydantic import ValidationError

from app.db import Device
from app.sandbox import browser
from app.sandbox.url_checks import analyse, brand_match, extract_urls, fold, is_apk_url, rule_verdict
from app.schemas import parse_action
from app.speech.language_style import classify_style
from app.welfare.scheduler import devices_needing_alert

IST = ZoneInfo("Asia/Kolkata")


# --------------------------------------------------------------------------- url checks
@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("http://sbi-kyc-update.in/verify", "dangerous"),
        ("https://onlinesbi-verify.com/login", "dangerous"),
        ("http://hdfcbank-secure.xyz", "dangerous"),
        ("https://amaz0n.in/offer", "dangerous"),
        ("https://flipcart.com/sale", "dangerous"),
        ("https://paytrn.com/kyc", "dangerous"),
        ("http://bit.ly/bses-pay.apk", "dangerous"),
        ("http://192.168.4.20/pay", "suspicious"),
        ("http://xn--sbi-1ma.com", "dangerous"),
        ("https://www.amazon.in/dp/B00ABC", "safe"),
        ("https://www.irctc.co.in", "safe"),
        ("https://www.wikipedia.org", "safe"),
    ],
)
def test_rule_verdicts(url: str, expected: str) -> None:
    facts = analyse(url)
    assert rule_verdict(facts)[0] == expected, facts.signals


def test_apk_rules() -> None:
    assert is_apk_url("example.com/app.apk")
    assert is_apk_url("http://x.top/download?id=1")
    assert not is_apk_url("https://www.amazon.in/apple")


def test_punycode_and_homoglyph_folding() -> None:
    facts = analyse("http://xn--pple-43d.com")  # Cyrillic а in "apple"
    assert any(s.startswith("punycode") for s in facts.signals)
    assert fold("ѕbі") == "sbi"
    assert brand_match("phonepé.com".encode("idna").decode())[1] in {"lookalike", "impersonation"}


def test_ip_host_flagged() -> None:
    assert any(s.startswith("ip_host") for s in analyse("http://10.1.2.3/kyc").signals)


def test_extract_urls_finds_bare_domains_and_shorteners() -> None:
    text = "Update KYC at sbi-kyc.in/now or http://bit.ly/x1 today. Thanks."
    assert extract_urls(text) == ["sbi-kyc.in/now", "http://bit.ly/x1"]


async def test_expand_url_follows_redirects_without_network() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "bit.ly":
            return httpx.Response(301, headers={"location": "http://sbi-login.top/x"})
        return httpx.Response(200)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        chain = await browser.expand_url("http://bit.ly/abc", client)
    assert chain == ["http://bit.ly/abc", "http://sbi-login.top/x"]
    assert rule_verdict(analyse(chain[-1]))[0] == "dangerous"


async def test_expand_url_stops_after_five_hops() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        n = int(request.url.path.strip("/") or 0)
        return httpx.Response(302, headers={"location": f"/{n + 1}"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        chain = await browser.expand_url("http://loop.example/0", client)
    assert len(chain) == 6


# --------------------------------------------------------------------------- language style
@pytest.mark.parametrize(
    ("text", "code", "style"),
    [
        ("book a cab", None, "en"),
        ("What are my updates today", None, "en"),
        ("कैब बुक करो", None, "hi"),
        ("मुझे अपोलो अस्पताल जाना है", "hi-IN", "hi"),
        ("Apollo jaane ke liye cab book kar do", None, "hinglish"),
        ("मुझे Apollo hospital के लिए cab चाहिए", "hi-IN", "hinglish"),
        ("mera bill kitna hai", None, "hinglish"),
        ("cab book karo", "hi-IN", "hinglish"),
        ("what do I do", None, "en"),
        # English heard with an Indian accent comes back as hi-IN from Sarvam: still English.
        ("i want to send this photo to keshav", "hi-IN", "en"),
        ("I want to send this photo to Keshav.", "hi-IN", "en"),
        # Sarvam's codemix writes Indian names in Devanagari; one name doesn't make English into Hinglish.
        ("i want to send this photo to केशव", "hi-IN", "en"),
        ("call राम कुमार now please", "hi-IN", "en"),
        ("Keshav ko photo bhejo", "hi-IN", "hinglish"),
    ],
)
def test_language_style(text: str, code: str | None, style: str) -> None:
    assert classify_style(text, code) == style


# --------------------------------------------------------------------------- action schema
@pytest.mark.parametrize(
    "raw",
    [
        {"action": "tap", "id": 14, "reason": "r"},
        {"action": "type", "id": 15, "text": "Apollo Hospital"},
        {"action": "scroll", "direction": "down"},
        {"action": "confirm", "summary": "Book it?", "then": {"action": "tap", "id": 31}},
        {"action": "highlight", "instruction": "Swipe up slowly", "gesture": "swipe_up"},
        {"action": "done", "message": "Booked", "id": None},
    ],
)
def test_valid_actions(raw: dict) -> None:
    assert parse_action(raw).action == raw["action"]


@pytest.mark.parametrize(
    "raw",
    [
        {"action": "tap"},
        {"action": "explode"},
        {"action": "scroll", "direction": "sideways"},
        {"action": "confirm", "summary": "x", "then": {"action": "open_app", "package": "com.evil"}},
        {"action": "ask_user", "question": ""},
        {"action": "wait", "ms": 999_999},
    ],
)
def test_invalid_actions(raw: dict) -> None:
    with pytest.raises(ValidationError):
        parse_action(raw)


# --------------------------------------------------------------------------- welfare logic
def _device(last: datetime | None, alerted: str | None = None, contacts: bool = True) -> Device:
    return Device(
        device_id="d1",
        user_name="Kamla",
        contacts=[{"name": "R", "telegram_chat_id": "1"}] if contacts else [],
        check_time="12:00",
        last_heartbeat_ms=int(last.timestamp() * 1000) if last else None,
        last_alert_date=alerted,
    )


def test_welfare_alerts_when_no_unlock_since_8pm_yesterday() -> None:
    now = datetime(2026, 10, 31, 12, 5, tzinfo=IST)
    due = devices_needing_alert(now, [_device(datetime(2026, 10, 30, 19, 14, tzinfo=IST))])
    assert len(due) == 1
    assert "yesterday 7:14 pm" in due[0].text and "Kamla ji" in due[0].text


def test_welfare_quiet_when_unlocked_after_cutoff() -> None:
    now = datetime(2026, 10, 31, 12, 5, tzinfo=IST)
    assert devices_needing_alert(now, [_device(datetime(2026, 10, 30, 20, 14, tzinfo=IST))]) == []


def test_welfare_waits_for_check_time_and_alerts_once() -> None:
    old = datetime(2026, 10, 29, 9, 0, tzinfo=IST)
    assert devices_needing_alert(datetime(2026, 10, 31, 11, 59, tzinfo=IST), [_device(old)]) == []
    assert (
        devices_needing_alert(datetime(2026, 10, 31, 13, 0, tzinfo=IST), [_device(old, "2026-10-31")]) == []
    )
    assert (
        devices_needing_alert(datetime(2026, 10, 31, 13, 0, tzinfo=IST), [_device(old, contacts=False)]) == []
    )


def test_welfare_alerts_device_that_never_checked_in() -> None:
    due = devices_needing_alert(datetime(2026, 10, 31, 12, 0, tzinfo=IST), [_device(None)])
    assert len(due) == 1 and due[0].day == date(2026, 10, 31)


def test_bare_domain_is_not_flagged_insecure():
    from app.sandbox.url_checks import analyse, rule_verdict

    bare = analyse("airtel.in/pay")
    assert not any(s.startswith("no_https") for s in bare.signals)
    assert rule_verdict(bare)[0] == "safe"
    explicit = analyse("http://airtel-offers.example/pay")
    assert any(s.startswith("no_https") for s in explicit.signals)
    assert rule_verdict(explicit)[0] == "suspicious"


def test_intent_rules_photo_send_is_guide_and_app_name_is_not_product() -> None:
    from app.llm.rules_intent import detect_intent, extract_slots

    assert detect_intent("mujhe ye photo Aarav ko bhejni hai") == "guide"
    assert detect_intent("how do I send this photo to my grandson") == "guide"
    assert detect_intent("Flipkart se biscuit mangao") == "shop_online"
    assert extract_slots("Flipkart se biscuit mangao", "shop_online").product == "biscuit"
    assert extract_slots("order Parle-G biscuits from Amazon", "shop_online").product == "Parle-G biscuits"
