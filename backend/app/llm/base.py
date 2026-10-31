"""LLM client interface shared by the Gemini and mock implementations."""

from __future__ import annotations

from typing import Any, Protocol

from pydantic import BaseModel

from app.config import Settings
from app.llm.clinic_call import ClinicCallRequest, ClinicCallTurn
from app.sandbox.url_checks import UrlFacts
from app.schemas import (
    AgentStepRequest,
    Appointment,
    ClassifiedMessage,
    DigestRequest,
    IntentResponse,
    LinkVerdict,
    MessageIn,
)


class LLMClient(Protocol):
    async def agent_step(self, req: AgentStepRequest) -> dict[str, Any]: ...

    async def intent(self, utterance: str, style: str) -> IntentResponse: ...

    async def classify(self, messages: list[MessageIn]) -> list[ClassifiedMessage]: ...

    async def extract_appointment(self, screen_text: str) -> Appointment: ...

    async def digest(self, req: DigestRequest) -> str: ...

    async def link_verdict(
        self, facts: UrlFacts, signals: list[str], sandbox: dict[str, Any] | None
    ) -> LinkVerdict: ...

    async def clinic_call_turn(self, req: ClinicCallRequest) -> ClinicCallTurn: ...

    async def structured(
        self, route: str, system: str, prompt: str, schema: type[BaseModel]
    ) -> BaseModel | None:
        """One JSON answer for `route` (models.yaml), or None so the caller uses its rule-based fallback."""
        ...


_client: LLMClient | None = None


def get_llm(settings: Settings) -> LLMClient:
    global _client
    if _client is None:
        if settings.mock:
            from app.llm.mock_client import MockClient

            _client = MockClient(settings)
        else:
            from app.llm.gemini_client import GeminiClient

            _client = GeminiClient(settings)
    return _client


def reset_llm() -> None:
    global _client
    _client = None
