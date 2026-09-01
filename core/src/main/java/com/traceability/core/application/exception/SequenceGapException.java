package com.traceability.core.application.exception;

/**
 * Thrown when appending an event with an expected sequence that does not match the current DB sequence.
 * This indicates a caller bug, not a concurrency issue.
 */
public class SequenceGapException extends RuntimeException {
    public SequenceGapException(String message) {
        super(message);
    }
}
