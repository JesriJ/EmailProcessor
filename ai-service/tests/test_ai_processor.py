from app.config import Settings
from app.errors import AiInvalidOutputError
from app.models import ProcessEmailRequest
from app.services.ai_processor import AiProcessor
from app.services.llm_client import MockLlmClient


def test_mock_process_billing():
    settings = Settings(use_mock_llm=True, llm_provider="mock", openai_api_key="")
    processor = AiProcessor(settings)
    result = processor.process(
        ProcessEmailRequest(
            emailId="e1",
            subject="Duplicate charge",
            body="I was charged twice for my order",
            attempt=1,
        )
    )
    assert result.classification in processor.categories
    assert result.summary
    assert result.priority in {"LOW", "MEDIUM", "HIGH", "URGENT"}
    assert result.suggestedAction
    assert result.model
    assert result.promptVersion


def processor_categories(settings: Settings):
    from app.config import load_categories

    return list(load_categories(settings).get("categories") or [])


def test_invalid_classification_rejected():
    settings = Settings(use_mock_llm=True)

    class BadClient(MockLlmClient):
        def complete_structured(self, *, system_prompt, user_prompt, schema):
            return (
                {
                    "classification": "not-a-real-category",
                    "summary": "x",
                    "priority": "LOW",
                    "actionRequired": False,
                    "suggestedAction": "y",
                },
                1,
                1,
            )

    processor = AiProcessor(settings, llm=BadClient(["billing", "other"]))
    try:
        processor.process(ProcessEmailRequest(emailId="e1", subject="x", body="y", attempt=1))
        assert False, "expected AiInvalidOutputError"
    except AiInvalidOutputError:
        pass
