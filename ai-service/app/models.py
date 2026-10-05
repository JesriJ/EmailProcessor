from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


Priority = Literal["LOW", "MEDIUM", "HIGH", "URGENT"]


class ProcessEmailRequest(BaseModel):
    emailId: str = Field(min_length=1)
    subject: str = ""
    body: str = ""
    attempt: int = Field(default=1, ge=1)


class ProcessEmailResponse(BaseModel):
    classification: str
    summary: str
    priority: Priority
    actionRequired: bool
    suggestedAction: str
    model: str
    promptVersion: str
    inputTokens: int | None = None
    outputTokens: int | None = None
