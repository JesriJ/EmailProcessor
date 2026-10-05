from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # mock | ollama | openai
    llm_provider: str = "ollama"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    ollama_base_url: str = "http://host.docker.internal:11434"
    ollama_model: str = "qwen3:8b"
    prompt_version: str = "v1"
    categories_file: str = "/config/categories.lock"
    categories_fallback_file: str = "/config/categories.yml"
    use_mock_llm: bool = False
    host: str = "0.0.0.0"
    port: int = 8000


@lru_cache
def get_settings() -> Settings:
    return Settings()


def load_categories(settings: Settings | None = None) -> dict[str, Any]:
    settings = settings or get_settings()
    for candidate in (settings.categories_file, settings.categories_fallback_file):
        path = Path(candidate)
        if not path.exists():
            # local/dev fallbacks
            local = Path(__file__).resolve().parents[2] / "config" / Path(candidate).name
            path = local if local.exists() else path
        if path.exists():
            text = path.read_text(encoding="utf-8")
            if path.suffix == ".json" or path.name.endswith(".lock"):
                try:
                    return json.loads(text)
                except json.JSONDecodeError:
                    return yaml.safe_load(text)
            return yaml.safe_load(text)
    return {
        "domain": "customer_support",
        "promptVersion": settings.prompt_version,
        "categories": [
            "billing",
            "technical",
            "shipping",
            "account",
            "product",
            "complaint",
            "other",
        ],
    }
