package com.traceability.core.application.command;

public class ConcurrencyRetryExhaustedException extends RuntimeException {
    public ConcurrencyRetryExhaustedException(String message) {
        super(message);
    }

    public ConcurrencyRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
