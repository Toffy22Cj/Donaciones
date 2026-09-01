package com.traceability.core.application.exception;

/**
 * Thrown when an optimistic concurrency conflict occurs (e.g. unique constraint violation on streamId + sequence).
 * Unchecked exception to trigger transaction rollback.
 */
public class ConcurrencyConflictException extends RuntimeException {
    public ConcurrencyConflictException(String message) {
        super(message);
    }
    
    public ConcurrencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
