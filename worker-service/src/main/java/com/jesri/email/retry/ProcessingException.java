package com.jesri.email.retry;

public class ProcessingException extends RuntimeException {

    private final FailureType failureType;

    public ProcessingException(FailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public ProcessingException(FailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType getFailureType() {
        return failureType;
    }
}
