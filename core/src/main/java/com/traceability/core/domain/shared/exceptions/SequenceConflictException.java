package com.traceability.core.domain.shared.exceptions;

/**
 * Thrown when an optimistic concurrency conflict occurs (incoming sequence != expected sequence).
 * 
 * Ref: ADR-013 (Validación de secuencia concurrente)
 */
public class SequenceConflictException extends RuntimeException {
    public SequenceConflictException(String streamId, long expected, long actual) {
        super(String.format("Sequence conflict for stream %s: expected %d, got %d", streamId, expected, actual));
    }
}
