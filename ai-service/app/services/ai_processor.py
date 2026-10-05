from __future__ import annotations

from typing import Any

from app.config import Settings, load_categories
from app.errors import AiInvalidOutputError
from app.models import ProcessEmailRequest, ProcessEmailResponse
from app.services.llm_client import LlmClient, MockLlmClient, OllamaLlmClient, OpenAiLlmClient


PRIORITY_POLICY = """
Priority policy:
- LOW: informational, no deadline
- MEDIUM: normal customer request
- HIGH: billing errors, outages, blocked users, angry customers
- URGENT: safety, fraud, legal, complete service outage, explicit urgency
"""


class AiProcessor:
    def __init__(self, settings: Settings, llm: LlmClient | None = None):
        self.settings = settings
        self.categories_doc = load_categories(settings)
        self.categories = list(self.categories_doc.get("categories") or [])
        self.prompt_version = str(
            self.categories_doc.get("promptVersion") or settings.prompt_version
        )
        if llm is not None:
            self.llm = llm
            self.model_name = getattr(llm, "model", "injected-llm")
        else:
            self.llm, self.model_name = self._build_llm(settings)

    def _build_llm(self, settings: Settings) -> tuple[LlmClient, str]:
        provider = (settings.llm_provider or "ollama").strip().lower()
        if settings.use_mock_llm or provider == "mock":
            return MockLlmClient(self.categories, model="mock-llm"), "mock-llm"
        if provider == "openai":
            if not settings.openai_api_key:
                raise AiInvalidOutputError("OPENAI_API_KEY is required when LLM_PROVIDER=openai")
            return (
                OpenAiLlmClient(settings.openai_api_key, settings.openai_model),
                settings.openai_model,
            )
        if provider == "ollama":
            return (
                OllamaLlmClient(settings.ollama_base_url, settings.ollama_model),
                settings.ollama_model,
            )
        raise AiInvalidOutputError(f"Unsupported LLM_PROVIDER: {provider}")

    def process(self, request: ProcessEmailRequest) -> ProcessEmailResponse:
        schema = self._schema()
        system_prompt = self._system_prompt()
        user_prompt = (
            f"emailId: {request.emailId}\n"
            f"subject: {request.subject}\n"
            f"body: {request.body}\n"
            f"attempt: {request.attempt}\n"
        )
        try:
            payload, input_tokens, output_tokens = self.llm.complete_structured(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                schema=schema,
            )
        except Exception as exc:  # noqa: BLE001
            raise AiInvalidOutputError(f"LLM call failed: {exc}") from exc

        try:
            normalized = self._normalize_payload(payload)
            response = ProcessEmailResponse(
                classification=normalized["classification"],
                summary=normalized["summary"],
                priority=normalized["priority"],  # type: ignore[arg-type]
                actionRequired=normalized["actionRequired"],
                suggestedAction=normalized["suggestedAction"],
                model=self.model_name,
                promptVersion=self.prompt_version,
                inputTokens=input_tokens,
                outputTokens=output_tokens,
            )
        except Exception as exc:  # noqa: BLE001
            raise AiInvalidOutputError(f"Schema validation failed: {exc}") from exc

        if response.classification not in self.categories:
            raise AiInvalidOutputError(
                f"classification '{response.classification}' not in locked categories"
            )
        return response

    def _normalize_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        classification = str(payload.get("classification", "")).strip().lower()
        aliases = {
            "bill": "billing",
            "billing/invoice": "billing",
            "invoice": "billing",
            "payment": "billing",
            "tech": "technical",
            "technical support": "technical",
            "support": "technical",
            "bug": "technical",
            "ship": "shipping",
            "delivery": "shipping",
            "package": "shipping",
            "acct": "account",
            "login": "account",
            "pricing": "product",
            "sales": "product",
            "feature": "product",
            "angry": "complaint",
            "feedback": "complaint",
            "misc": "other",
            "general": "other",
            "unknown": "other",
        }
        if classification not in self.categories:
            classification = aliases.get(classification, classification)
        if classification not in self.categories:
            # Prefer a locked fallback over failing the whole email job.
            classification = "other" if "other" in self.categories else (self.categories[0] if self.categories else "other")
        summary = str(payload.get("summary", "")).strip()
        priority_raw = str(payload.get("priority", "")).strip().upper()
        priority_map = {
            "L": "LOW",
            "LOW": "LOW",
            "M": "MEDIUM",
            "MED": "MEDIUM",
            "MEDIUM": "MEDIUM",
            "H": "HIGH",
            "HIGH": "HIGH",
            "U": "URGENT",
            "URGENT": "URGENT",
        }
        priority = priority_map.get(priority_raw, "")
        if not priority:
            # Local models occasionally omit priority; default rather than fail the job.
            priority = "MEDIUM"
        action_raw = payload.get("actionRequired")
        if isinstance(action_raw, bool):
            action_required = action_raw
        elif isinstance(action_raw, (int, float)):
            action_required = bool(action_raw)
        else:
            text = str(action_raw or "").strip().lower()
            if text in {"true", "yes", "1", "y"}:
                action_required = True
            elif text in {"false", "no", "0", "n", ""}:
                action_required = False
            else:
                # Model sometimes returns a sentence; treat non-empty as true.
                action_required = True
        suggested = str(payload.get("suggestedAction", "")).strip()
        if not suggested and isinstance(action_raw, str) and action_raw.strip():
            # Recover when model swapped actionRequired/suggestedAction semantics.
            suggested = action_raw.strip()
        if not suggested:
            suggested = "Review the email and take appropriate action."
        if not summary:
            summary = "Customer email received."
        return {
            "classification": classification,
            "summary": summary,
            "priority": priority,
            "actionRequired": action_required,
            "suggestedAction": suggested,
        }

    def _system_prompt(self) -> str:
        cats = ", ".join(self.categories)
        return (
            "You are an email operations assistant. "
            "Return only a JSON object with the required fields. "
            f"classification must be one of: {cats}. "
            "priority must be exactly LOW, MEDIUM, HIGH, or URGENT. "
            "actionRequired must be JSON boolean true or false. "
            "suggestedAction must be a short imperative sentence. "
            f"{PRIORITY_POLICY} "
            f"promptVersion={self.prompt_version}."
        )

    def _schema(self) -> dict[str, Any]:
        return {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "classification": {"type": "string", "enum": self.categories},
                "summary": {"type": "string"},
                "priority": {
                    "type": "string",
                    "enum": ["LOW", "MEDIUM", "HIGH", "URGENT"],
                },
                "actionRequired": {"type": "boolean"},
                "suggestedAction": {"type": "string"},
            },
            "required": [
                "classification",
                "summary",
                "priority",
                "actionRequired",
                "suggestedAction",
            ],
        }
