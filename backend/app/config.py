"""Environment and models.yaml loading."""

from __future__ import annotations

import logging
import os
import secrets
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml

log = logging.getLogger("saathi.config")
APP_DIR = Path(__file__).resolve().parent
REPO_ROOT = APP_DIR.parent.parent


def _load_dotenv() -> None:
    """Minimal .env loader (repo root or backend/); real env vars win."""
    for candidate in (REPO_ROOT / ".env", APP_DIR.parent / ".env"):
        if not candidate.exists():
            continue
        for raw in candidate.read_text("utf-8").splitlines():
            line = raw.split("#", 1)[0].strip()
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


def _flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    gemini_api_key: str
    sarvam_api_key: str
    safe_browsing_api_key: str
    telegram_bot_token: str
    mock: bool
    sandbox_enabled: bool
    welfare_check_time: str
    welfare_prompt_time: str
    timezone: str
    database_path: Path
    fixtures_dir: Path
    models: dict[str, Any] = field(default_factory=dict)
    # Family dashboard: HMAC secret for per-device keys, and the public origin used in shared links.
    dashboard_secret: str = ""
    public_base_url: str = ""

    def route(self, name: str) -> dict[str, Any]:
        return self.models.get(name, {})

    @property
    def sarvam_enabled(self) -> bool:
        return bool(self.sarvam_api_key)

    @property
    def telegram_enabled(self) -> bool:
        return bool(self.telegram_bot_token)


def _database_path(url: str) -> Path:
    if url.startswith("sqlite:///"):
        url = url[len("sqlite:///") :]
    path = Path(url)
    return path if path.is_absolute() else (APP_DIR.parent / path).resolve()


@lru_cache
def get_settings() -> Settings:
    _load_dotenv()
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
    models = yaml.safe_load((APP_DIR / "models.yaml").read_text("utf-8")) or {}
    fixtures = os.environ.get("FIXTURES_DIR") or str(REPO_ROOT / "fixtures")
    dashboard_secret = os.environ.get("DASHBOARD_SECRET", "").strip()
    if not dashboard_secret:
        dashboard_secret = secrets.token_hex(32)
        log.warning("DASHBOARD_SECRET not set: family dashboard links change on every restart")
    return Settings(
        gemini_api_key=gemini_key,
        sarvam_api_key=os.environ.get("SARVAM_API_KEY", "").strip(),
        safe_browsing_api_key=os.environ.get("SAFE_BROWSING_API_KEY", "").strip(),
        telegram_bot_token=os.environ.get("TELEGRAM_BOT_TOKEN", "").strip(),
        mock=_flag("MOCK") or not gemini_key,
        sandbox_enabled=_flag("SANDBOX_ENABLED"),
        welfare_check_time=os.environ.get("WELFARE_CHECK_TIME", "12:00").strip(),
        welfare_prompt_time=os.environ.get("WELFARE_PROMPT_TIME", "11:30").strip(),
        timezone=os.environ.get("TIMEZONE", "Asia/Kolkata").strip(),
        database_path=_database_path(os.environ.get("DATABASE_URL", "sqlite:///./saathi.db")),
        fixtures_dir=Path(fixtures),
        models=models,
        dashboard_secret=dashboard_secret,
        public_base_url=os.environ.get("PUBLIC_BASE_URL", "").strip().rstrip("/"),
    )
