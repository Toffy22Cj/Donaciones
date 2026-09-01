package com.traceability.core.domain.event;

import java.time.Instant;

/**
 * The immutable cryptographic envelope for the Event Store (Persistence layer).
 * Contains infrastructure metadata and cryptographic proofs.
 * 
 * Ref: ADR-013 (Identidad inmutable y separación de payload vs sobre)
 */
public record TraceabilityEvent(
    String eventId,
    String streamId,
    long sequence,
    String idempotencyKey,
    String eventType,
    String schemaVersion,
    Instant occurredAt,
    Instant recordedAt,
    String actorId,
    DomainEventPayload payload,
    String previousHash,
    String eventHash
) {
}
