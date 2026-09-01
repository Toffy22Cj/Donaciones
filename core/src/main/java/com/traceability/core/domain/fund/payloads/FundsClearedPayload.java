package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for FUNDS_CLEARED event (Genesis 2 or subsequent clearing).
 */
public record FundsClearedPayload(
    long clearedAmount,
    String sourceReference
) implements DomainEventPayload {}
