package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for FUND_REGISTERED event (Genesis 1) with schemaVersion 2.0.
 * Includes organizationRef.
 */
public record FundRegisteredV2Payload(
    String organizationRef,
    Long pledgedAmount,
    String currency,
    String campaignRef,
    String donorRef
) implements DomainEventPayload {}
