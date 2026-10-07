package com.traceability.core.application.saga;

import java.time.Instant;

/**
 * Record representing an outbox message for Saga orchestration.
 * Note: Designed to be agnostic of persistence mechanism (no Spring/Mongo annotations).
 *
 * <p>ADR-007/008 Enmienda 1: {@code resolutionStartedAt} marca el inicio de la fase de resolución ({@code null} mientras
 * se está en ejecución); {@code lastFailureReason}, el último motivo de fallo; los campos {@code manual*}, la resolución
 * manual auditada (D5).
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
    Instant nextRetryAt,
    Instant resolutionStartedAt,
    String lastFailureReason,
    String manualResolvedBy,
    String manualNote,
    Instant manualResolvedAt
) {

    /** Mensaje en fase de ejecución, sin datos de resolución. */
    public OutboxMessage(String messageId, String sagaType, String sourceAggregateId, String correlationId,
                         String payload, OutboxStatus status, int retryCount, Instant createdAt, Instant nextRetryAt) {
        this(messageId, sagaType, sourceAggregateId, correlationId, payload, status, retryCount, createdAt, nextRetryAt,
                null, null, null, null, null);
    }

    public boolean inResolution() {
        return resolutionStartedAt != null;
    }

    public OutboxMessage withState(OutboxStatus newStatus, int newRetryCount, Instant newNextRetryAt,
                                   Instant newResolutionStartedAt, String newLastFailureReason) {
        return new OutboxMessage(messageId, sagaType, sourceAggregateId, correlationId, payload, newStatus, newRetryCount,
                createdAt, newNextRetryAt, newResolutionStartedAt, newLastFailureReason, manualResolvedBy, manualNote,
                manualResolvedAt);
    }

    public OutboxMessage withManualResolution(String operator, String note, Instant at) {
        return new OutboxMessage(messageId, sagaType, sourceAggregateId, correlationId, payload, OutboxStatus.RESOLVED,
                retryCount, createdAt, nextRetryAt, resolutionStartedAt, lastFailureReason, operator, note, at);
    }
}
