"""Real Gemini client (google-genai). Every JSON route: response schema → Pydantic → one retry → fallback."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from datetime import date
from typing import Any

from google import genai
from google.genai import types
from pydantic import BaseModel, ValidationError

from app.config import Settings
from app.llm import clinic_call, rules_intent, rules_messages
from app.llm.clinic_call import ClinicCallRequest, ClinicCallTurn
from app.llm.prompts import render
from app.sandbox.url_checks import UrlFacts, rule_verdict
from app.schemas import (
    GUIDE_ACTIONS,
    AgentStepRequest,
    Appointment,
    ClassifiedMessage,
    ClassifyResponse,
    DigestRequest,
    FlatAction,
    IntentResponse,
    LinkVerdict,
    MessageIn,
    Slots,
    dump_action,
    parse_action,
)

log = logging.getLogger("saathi.gemini")


class _IntentOut(BaseModel):
    intent: str
    slots: Slots
    reply_text: str


def _fail(message: str, reason: str) -> dict[str, Any]:
    return {"action": "fail", "message": message, "reason": reason}


def _with_when(item: dict[str, Any], today: date) -> dict[str, Any]:
    """Models get weekday arithmetic wrong ("next Friday" for a Sunday), so the phrase is computed here."""
    due = item.get("due_date")
    if due:
        try:
            d = date.fromisoformat(due)
        except ValueError:
            return item
        days = (d - today).days
        item["when"] = (
            "today"
            if days == 0
            else "tomorrow"
            if days == 1
            else f"on {d.strftime('%A')}"
            if 1 < days < 7
            else f"on {d.day} {d.strftime('%B')}"
            if days >= 7
            else f"overdue since {d.day} {d.strftime('%B')}"
        )
    return item


class GeminiClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = genai.Client(api_key=settings.gemini_api_key)

    async def _generate(
        self, route: str, system: str, contents: list[Any], schema: type[BaseModel] | None
    ) -> str:
        cfg = self.settings.route(route)
        config = types.GenerateContentConfig(
            system_instruction=system,
            temperature=float(cfg.get("temperature", 0.2)),
            response_mime_type="application/json" if schema else "text/plain",
            response_json_schema=schema.model_json_schema() if schema else None,
            thinking_config=types.ThinkingConfig(thinking_level=cfg["thinking"])
            if cfg.get("thinking")
            else None,
        )
        models = [cfg["model"], *([cfg["fallback"]] if cfg.get("fallback") else [])]
        timeout = float(cfg.get("timeout_s", 8))
        for i, model in enumerate(models):
            started = time.perf_counter()
            try:
                resp = await asyncio.wait_for(
                    self.client.aio.models.generate_content(model=model, contents=contents, config=config),
                    timeout,
                )
                return resp.text or ""
            except Exception as exc:
                # Overloaded (503/429) or slow: try the fallback model once; anything else is a real error.
                retryable = isinstance(exc, TimeoutError) or any(
                    c in str(exc)[:12] for c in ("503", "429", "500")
                )
                if i + 1 < len(models) and retryable:
                    log.info(
                        "route=%s model=%s failed (%s); trying fallback", route, model, type(exc).__name__
                    )
                    continue
                raise
            finally:
                log.info(
                    "route=%s model=%s latency_ms=%d", route, model, (time.perf_counter() - started) * 1000
                )
        raise RuntimeError("unreachable")

    async def _json(self, route: str, system: str, contents: list[Any], schema: type[BaseModel]) -> BaseModel:
        """Validate with Pydantic; on failure retry once with the error appended. Raises on second failure."""
        text = await self._generate(route, system, contents, schema)
        try:
            return schema.model_validate_json(text)
        except ValidationError as exc:
            retry = [
                *contents,
                f"Your previous reply was invalid: {exc.errors()[:3]}. Reply again with valid JSON.",
            ]
            return schema.model_validate_json(await self._generate(route, system, retry, schema))

    # ------------------------------------------------------------------ skill compiler
    async def compile_json(self, route: str, system: str, user: str) -> tuple[str, int]:
        """Raw JSON text from the strong model for the skill compiler; returns (text, approx tokens)."""
        text = await self._generate(route, system, [user], None)
        text = text.strip()
        if text.startswith("```"):
            text = text.strip("`").removeprefix("json").strip()
        return text, (len(system) + len(user) + len(text)) // 4

    # ------------------------------------------------------------------ agent
    async def agent_step(self, req: AgentStepRequest) -> dict[str, Any]:
        fails = sum(1 for h in req.history[-2:] if h.action.get("action") in {"fail", "back"})
        route = "agent_step_hard" if len(req.history) >= 2 and fails == 2 else "agent_step"
        system = render("agent_step", reply_style=req.language)
        history = "\n".join(json.dumps(h.action, ensure_ascii=False) for h in req.history[-6:]) or "(none)"
        prompt = (
            f"Mode: {req.mode}\nGoal: {req.goal}\nTask: {req.task_hint or '-'}\nReply style: {req.language}\n"
            f"Recent actions:\n{history}\n\nCurrent screen:\n{req.screen_text}"
        )
        contents: list[Any] = [prompt]
        if req.screenshot_b64:
            contents.insert(
                0, types.Part.from_bytes(data=base64.b64decode(req.screenshot_b64), mime_type="image/jpeg")
            )
        for attempt in range(2):
            try:
                text = await self._generate(route, system, contents, FlatAction)
                action = parse_action(json.loads(text))
                if req.mode == "GUIDE" and action.action not in GUIDE_ACTIONS:
                    raise ValueError(f"{action.action} is not allowed in GUIDE mode")
                return dump_action(action)
            except (ValidationError, ValueError, json.JSONDecodeError) as exc:
                log.info("agent_step invalid output (attempt %d): %s", attempt + 1, type(exc).__name__)
                contents = [
                    *contents,
                    f"Your previous reply was invalid: {str(exc)[:300]}. Return one valid action.",
                ]
            except Exception as exc:
                log.warning("agent_step error: %s", type(exc).__name__)
                break
        return _fail(
            "I couldn't work out the next step. Shall I show you instead?", "model error or invalid output"
        )

    # ------------------------------------------------------------------ intent
    async def intent(self, utterance: str, style: str) -> IntentResponse:
        try:
            out = await self._json(
                "intent_router",
                render("intent", reply_style=style),
                [f"Reply style: {style}\nUtterance: {utterance}"],
                _IntentOut,
            )
            assert isinstance(out, _IntentOut)
            return IntentResponse.model_validate(
                {"intent": out.intent, "slots": out.slots, "reply_style": style, "reply_text": out.reply_text}
            )
        except Exception as exc:
            log.info("intent fallback to rules: %s", type(exc).__name__)
            return rules_intent.route(utterance, style)

    # ------------------------------------------------------------------ messages
    async def classify(self, messages: list[MessageIn]) -> list[ClassifiedMessage]:
        payload = json.dumps([m.model_dump() for m in messages], ensure_ascii=False)
        try:
            out = await self._json("classify_messages", render("classify"), [payload], ClassifyResponse)
            assert isinstance(out, ClassifyResponse)
            by_id = {r.id: r for r in out.results}
            return [by_id.get(m.id) or rules_messages.classify_one(m) for m in messages]
        except Exception as exc:
            log.info("classify fallback to rules: %s", type(exc).__name__)
            return [rules_messages.classify_one(m) for m in messages]

    async def extract_appointment(self, screen_text: str) -> Appointment:
        try:
            out = await self._json(
                "extract_appointment", render("extract_appointment"), [screen_text], Appointment
            )
            assert isinstance(out, Appointment)
            return out
        except Exception as exc:
            log.info("extract fallback to rules: %s", type(exc).__name__)
            return rules_messages.extract_appointment(screen_text)

    async def digest(self, req: DigestRequest) -> str:
        today = req.today or date.today().isoformat()
        items = [i for i in req.items if i.category != "promo"]
        system = render(
            "digest", reply_style=req.reply_style, today=today, user_name=req.user_name or "unknown"
        )
        try:
            text = await self._generate(
                "digest_writer",
                system,
                [
                    json.dumps(
                        [_with_when(i.model_dump(), date.fromisoformat(today)) for i in items],
                        ensure_ascii=False,
                    )
                ],
                None,
            )
            if text.strip():
                return text.strip()
        except Exception as exc:
            log.info("digest fallback to template: %s", type(exc).__name__)
        return rules_messages.write_digest(items, req.reply_style, req.user_name, date.fromisoformat(today))

    # ------------------------------------------------------------------ links
    async def link_verdict(
        self, facts: UrlFacts, signals: list[str], sandbox: dict[str, Any] | None
    ) -> LinkVerdict:
        floor_verdict, en, hi = rule_verdict(facts, signals)
        if floor_verdict == "dangerous":
            return LinkVerdict(verdict="dangerous", reason_simple=en, reason_simple_hi=hi)
        info = {
            "url": facts.url,
            "signals": signals,
            "official_brand": facts.brand if facts.official else None,
        }
        contents: list[Any] = []
        if sandbox:
            info |= {k: v for k, v in sandbox.items() if k != "screenshot_b64"}
            if sandbox.get("screenshot_b64"):
                contents.append(
                    types.Part.from_bytes(
                        data=base64.b64decode(sandbox["screenshot_b64"]), mime_type="image/jpeg"
                    )
                )
        contents.append(json.dumps(info, ensure_ascii=False))
        try:
            out = await self._json("link_verdict", render("link_verdict"), contents, LinkVerdict)
            assert isinstance(out, LinkVerdict)
            return out
        except Exception as exc:
            log.info("link verdict fallback to rules: %s", type(exc).__name__)
            return LinkVerdict(verdict=floor_verdict, reason_simple=en, reason_simple_hi=hi)  # type: ignore[arg-type]

    # ------------------------------------------------------------------ clinic call (stretch)
    async def clinic_call_turn(self, req: ClinicCallRequest) -> ClinicCallTurn:
        system = render(
            "clinic_call",
            patient=req.patient_name,
            task=clinic_call.task_brief(req),
            reply_style=req.reply_style,
        )
        lines = [f"{line.role.upper()}: {line.text}" for line in req.transcript] or ["(the clinic picked up)"]
        try:
            out = await self._json("clinic_call", system, ["\n".join(lines)], ClinicCallTurn)
            assert isinstance(out, ClinicCallTurn)
            return out
        except Exception as exc:
            log.info("clinic call fallback to script: %s", type(exc).__name__)
            return clinic_call.mock_turn(req)
