package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ALLOCATION_CONFIRMED event.
 */
public record AllocationConfirmedPayload(
    String allocationId
) implements DomainEventPayload {}
