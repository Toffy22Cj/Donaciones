package com.traceability.core.domain.fund.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for FUNDS_CLEARED event (Genesis 2 or subsequent clearing) with schemaVersion 2.0.
 * Includes organizationRef.
 */
public record FundsClearedV2Payload(
    String organizationRef,
    long clearedAmount,
    String sourceReference,
    String currency,
    String campaignRef,
    String donorRef
) implements DomainEventPayload {}
