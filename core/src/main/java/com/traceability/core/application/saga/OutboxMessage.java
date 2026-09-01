package com.traceability.core.application.saga;

import java.time.Instant;

/**
 * Record representing an outbox message for Saga orchestration.
 * Note: Designed to be agnostic of persistence mechanism (no Spring/Mongo annotations).
 */
public record OutboxMessage(
    String messageId,
    String sagaType,
    String sourceAggregateId,
    String correlationId,
    String payload,
    OutboxStatus status,
    int retryCount,
    Instant createdAt,
    Instant nextRetryAt
) {}
