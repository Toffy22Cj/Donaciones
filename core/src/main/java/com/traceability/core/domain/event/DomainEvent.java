package com.traceability.core.domain.event;

import java.time.Instant;

/**
 * The pure domain event emitted by an Aggregate.
 * It contains only business facts, ignoring infrastructure details.
 * 
 * Ref: ADR-013 (Identidad inmutable y separación de payload vs sobre)
 */
public record DomainEvent(
    EventType eventType,
    DomainEventPayload payload,
    Instant occurredAt
) {
    public static final String GENESIS_HASH = "GENESIS";
}
