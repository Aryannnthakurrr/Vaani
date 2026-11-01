"""Self-learning skills: compile repeated agent work into tested routines and code (docs/features/LEARNING.md §1)."""

from __future__ import annotations

from functools import lru_cache

from app.config import get_settings
from app.skills.engine import SkillEngine
from app.skills.recogniser import CodeSkills
from app.skills.store import SkillStore


async def notify_family(text: str) -> None:
    """Skills are app-level (not personal), so every registered family member hears about them."""
    from app.deps import get_db
    from app.welfare.telegram import send_message

    settings = get_settings()
    seen: set[str] = set()
    for device in get_db().all_devices():
        for contact in device.contacts:
            chat = str(contact.get("telegram_chat_id") or "")
            if chat and chat not in seen:
                seen.add(chat)
                await send_message(settings, chat, text)


@lru_cache
def get_engine() -> SkillEngine:
    return SkillEngine(SkillStore(get_settings().database_path), notify=notify_family)


@lru_cache
def get_code_skills() -> CodeSkills:
    return CodeSkills(get_engine().store, notify=notify_family)
