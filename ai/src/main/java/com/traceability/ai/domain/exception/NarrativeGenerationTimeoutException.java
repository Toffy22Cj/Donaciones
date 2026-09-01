package com.traceability.ai.domain.exception;

public class NarrativeGenerationTimeoutException extends RuntimeException {
    public NarrativeGenerationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
