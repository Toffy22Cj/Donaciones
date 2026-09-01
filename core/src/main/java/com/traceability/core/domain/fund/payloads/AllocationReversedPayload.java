package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ALLOCATION_REVERSED event.
 */
public record AllocationReversedPayload(
    String allocationId,
    String reason
) implements DomainEventPayload {}
