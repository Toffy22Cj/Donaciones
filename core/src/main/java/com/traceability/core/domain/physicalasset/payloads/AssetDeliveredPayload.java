package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;
import java.time.Instant;

/**
 * Payload for ASSET_DELIVERED event.
 * Ref: ADR-014
 */
public record AssetDeliveredPayload(
    String finalCustodianRef,
    String beneficiaryRef,
    String locationRef,
    String evidenceRef,
    Instant deliveredAt
) implements DomainEventPayload {}
