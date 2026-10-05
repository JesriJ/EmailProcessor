class AiServiceError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 400):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class AiInvalidOutputError(AiServiceError):
    def __init__(self, message: str):
        super().__init__("AI_INVALID_OUTPUT", message, status_code=422)
