package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for FUND_REGISTERED event (Genesis 1).
 */
public record FundRegisteredPayload(
    Long pledgedAmount
) implements DomainEventPayload {}
