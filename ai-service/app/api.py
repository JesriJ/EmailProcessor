from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from app.config import Settings, get_settings
from app.errors import AiServiceError
from app.models import ProcessEmailRequest, ProcessEmailResponse
from app.services.ai_processor import AiProcessor

router = APIRouter()


def get_processor(settings: Settings = Depends(get_settings)) -> AiProcessor:
    return AiProcessor(settings)


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/process-email", response_model=ProcessEmailResponse)
def process_email(
    request: ProcessEmailRequest,
    processor: AiProcessor = Depends(get_processor),
) -> ProcessEmailResponse:
    try:
        return processor.process(request)
    except AiServiceError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc
