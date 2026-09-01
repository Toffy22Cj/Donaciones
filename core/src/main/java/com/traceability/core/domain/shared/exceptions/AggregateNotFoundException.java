package com.traceability.core.domain.shared.exceptions;

/**
 * Thrown when an aggregate is not found in the Event Store.
 */
public class AggregateNotFoundException extends RuntimeException {
    public AggregateNotFoundException(String aggregateId) {
        super(String.format("Aggregate not found: %s", aggregateId));
    }
}
