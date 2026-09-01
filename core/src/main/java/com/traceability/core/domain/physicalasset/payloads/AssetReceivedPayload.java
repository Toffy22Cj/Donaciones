package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ASSET_RECEIVED event.
 * Ref: ADR-003
 */
public record AssetReceivedPayload(
    String facilityLocation,
    String receiverRef
) implements DomainEventPayload {}
