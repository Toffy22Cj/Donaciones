package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ALLOCATION_REQUESTED event.
 */
public record AllocationRequestedPayload(
    String allocationId,
    long requestedAmount
) implements DomainEventPayload {}
