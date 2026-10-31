"""Mock LLM: replays fixture scripts for the agent and uses rules for everything else."""

from __future__ import annotations

import json
import logging
from datetime import date
from functools import cached_property
from typing import Any

from pydantic import BaseModel

from app.config import Settings
from app.llm import clinic_call, rules_intent, rules_messages
from app.llm.clinic_call import ClinicCallRequest, ClinicCallTurn
from app.sandbox.url_checks import UrlFacts, rule_verdict
from app.schemas import (
    AgentStepRequest,
    Appointment,
    ClassifiedMessage,
    DigestRequest,
    IntentResponse,
    LinkVerdict,
    MessageIn,
)

log = logging.getLogger("saathi.mock")


class MockClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    @cached_property
    def scripts(self) -> dict[str, dict[str, Any]]:
        folder = self.settings.fixtures_dir / "agent_scripts"
        scripts = {}
        for path in sorted(folder.glob("*.json")):
            data = json.loads(path.read_text("utf-8"))
            scripts[data.get("scenario", path.stem)] = data
        return scripts

    def pick_script(self, req: AgentStepRequest) -> dict[str, Any] | None:
        if req.task_hint and req.task_hint in self.scripts:
            return self.scripts[req.task_hint]
        goal = req.goal.lower()
        candidates = [s for s in self.scripts.values() if s.get("mode", "ACT") == req.mode] or list(
            self.scripts.values()
        )
        for script in candidates:
            if any(k.lower() in goal for k in script.get("goal_keywords", [])):
                return script
        return None

    async def agent_step(self, req: AgentStepRequest) -> dict[str, Any]:
        script = self.pick_script(req)
        if not script or not script.get("steps"):
            return {
                "action": "fail",
                "message": "I don't know how to do that yet. Shall I show you instead?",
                "reason": "mock: no script for this goal",
            }
        steps = script["steps"]
        start = len(req.history)
        for step in steps[start:]:
            if all(s in req.screen_text for s in step.get("when_contains", [])):
                return dict(step["action"])
        return dict(steps[min(start, len(steps) - 1)]["action"])

    async def intent(self, utterance: str, style: str) -> IntentResponse:
        return rules_intent.route(utterance, style)

    async def classify(self, messages: list[MessageIn]) -> list[ClassifiedMessage]:
        return [rules_messages.classify_one(m) for m in messages]

    async def extract_appointment(self, screen_text: str) -> Appointment:
        return rules_messages.extract_appointment(screen_text)

    async def digest(self, req: DigestRequest) -> str:
        today = date.fromisoformat(req.today) if req.today else date.today()
        return rules_messages.write_digest(req.items, req.reply_style, req.user_name, today)

    async def link_verdict(
        self, facts: UrlFacts, signals: list[str], sandbox: dict[str, Any] | None
    ) -> LinkVerdict:
        extra = list(signals)
        if sandbox and (sandbox.get("has_password") or sandbox.get("has_otp") or sandbox.get("has_card")):
            extra.append("asks_secrets: the page asks for a password, OTP or card number")
        verdict, en, hi = rule_verdict(facts, extra)
        return LinkVerdict(verdict=verdict, reason_simple=en, reason_simple_hi=hi)  # type: ignore[arg-type]

    async def clinic_call_turn(self, req: ClinicCallRequest) -> ClinicCallTurn:
        return clinic_call.mock_turn(req)

    async def structured(
        self, route: str, system: str, prompt: str, schema: type[BaseModel]
    ) -> BaseModel | None:
        return None  # every structured route has a rule-based fallback
