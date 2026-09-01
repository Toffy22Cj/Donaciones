package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for FUNDS_REFUNDED event.
 * Ref: ADR-004
 */
public record FundsRefundedPayload(
    String refundId,
    long refundAmount,
    boolean causedDeficit,
    String reason
) implements DomainEventPayload {}
