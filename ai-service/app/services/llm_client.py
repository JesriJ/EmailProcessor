from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any


class LlmClient(ABC):
    @abstractmethod
    def complete_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        schema: dict[str, Any],
    ) -> tuple[dict[str, Any], int | None, int | None]:
        """Return (payload, input_tokens, output_tokens)."""


class MockLlmClient(LlmClient):
    def __init__(self, categories: list[str], model: str = "mock-llm"):
        self.categories = categories
        self.model = model

    def complete_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        schema: dict[str, Any],
    ) -> tuple[dict[str, Any], int | None, int | None]:
        text = (user_prompt or "").lower()
        classification = "other" if "other" in self.categories else (self.categories[-1] if self.categories else "other")
        hint_key = "category_hint="
        if hint_key in text:
            hinted = text.split(hint_key, 1)[1].split()[0].strip("()[],.\"'")
            if hinted in self.categories:
                classification = hinted
        rules = {
            "billing": ("charge", "refund", "invoice", "tax", "billing"),
            "technical": ("crash", "bug", "error", "api", "login", "500"),
            "shipping": ("package", "shipping", "tracking", "shipment", "carrier", "delivered"),
            "account": ("password", "account", "delete my account", "email address"),
            "product": ("plan", "pricing", "feature", "sso", "premium", "business plan"),
            "complaint": ("rude", "frustrated", "complaint", "unhelpful", "outage this month"),
            "other": ("newsletter", "headquarters", "office hours", "unsubscribe"),
        }
        if classification == "other" or "category_hint=" not in text:
            for category, keywords in rules.items():
                if category in self.categories and any(k in text for k in keywords):
                    classification = category
                    break
        priority = "HIGH" if any(k in text for k in ("urgent", "asap", "immediately", "fraud")) else "MEDIUM"
        if "charged twice" in text:
            priority = "HIGH"
        action_required = priority in {"HIGH", "URGENT"} or any(
            k in text for k in ("please", "refund", "help", "delete")
        )
        summary = user_prompt.strip().splitlines()[0][:180] if user_prompt.strip() else "Email received."
        suggested = "Review and respond to the customer."
        if classification == "billing":
            suggested = "Review the billing issue and issue a refund if confirmed."
        elif classification == "technical":
            suggested = "Investigate the technical issue and follow up with a fix or workaround."
        payload = {
            "classification": classification,
            "summary": summary,
            "priority": priority,
            "actionRequired": action_required,
            "suggestedAction": suggested,
        }
        return payload, 120, 80


class OpenAiLlmClient(LlmClient):
    def __init__(self, api_key: str, model: str, base_url: str | None = None):
        from openai import OpenAI

        kwargs: dict[str, Any] = {"api_key": api_key or "unused"}
        if base_url:
            kwargs["base_url"] = base_url
        self.client = OpenAI(**kwargs)
        self.model = model
        self.supports_json_schema = base_url is None

    def complete_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        schema: dict[str, Any],
    ) -> tuple[dict[str, Any], int | None, int | None]:
        import json

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]
        kwargs: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": 0,
        }
        if self.supports_json_schema:
            kwargs["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "email_processing_result",
                    "strict": True,
                    "schema": schema,
                },
            }
        else:
            # Ollama / local OpenAI-compatible endpoints
            kwargs["response_format"] = {"type": "json_object"}
            messages[0]["content"] = (
                system_prompt
                + "\nRespond with a single JSON object matching this schema:\n"
                + json.dumps(schema)
            )

        response = self.client.chat.completions.create(**kwargs)
        content = response.choices[0].message.content or "{}"
        payload = _parse_json_object(content)
        usage = response.usage
        input_tokens = usage.prompt_tokens if usage else None
        output_tokens = usage.completion_tokens if usage else None
        return payload, input_tokens, output_tokens


class OllamaLlmClient(LlmClient):
    """Qwen / other Ollama models via native /api/chat (more reliable than /v1 on Windows)."""

    def __init__(self, base_url: str, model: str, timeout_seconds: float = 180.0):
        root = base_url.rstrip("/")
        # Accept either http://host:11434 or http://host:11434/v1
        if root.endswith("/v1"):
            root = root[:-3]
        self.base_url = root.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds

    def complete_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        schema: dict[str, Any],
    ) -> tuple[dict[str, Any], int | None, int | None]:
        import json

        import httpx

        # Native /api/chat + think=false avoids Qwen3 empty/hanging /v1 completions.
        system = (
            system_prompt
            + "\n/no_think\n"
            + "Respond with ONLY one JSON object. "
            + "actionRequired must be a boolean true/false (not a string). "
            + "priority must be exactly one of LOW, MEDIUM, HIGH, URGENT. "
            + "Schema:\n"
            + json.dumps(schema)
        )
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "think": False,
            "format": "json",
            # Keep model resident across the long 10k benchmark runs.
            "keep_alive": -1,
            "options": {
                "temperature": 0,
                "num_predict": 256,
            },
        }
        with httpx.Client(timeout=self.timeout_seconds) as client:
            response = client.post(f"{self.base_url}/api/chat", json=body)
            response.raise_for_status()
            data = response.json()

        message = data.get("message") or {}
        content = message.get("content") or ""
        if not content and message.get("reasoning"):
            content = str(message.get("reasoning"))
        content = content.replace("<think>", "").replace("</think>", "").strip() or "{}"
        payload = _parse_json_object(content)
        # Ollama native token fields
        input_tokens = data.get("prompt_eval_count")
        output_tokens = data.get("eval_count")
        return payload, input_tokens, output_tokens


def _parse_json_object(content: str) -> dict[str, Any]:
    import json
    import re

    text = content.strip()
    # Strip common markdown fences
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            raise
        return json.loads(match.group(0))
