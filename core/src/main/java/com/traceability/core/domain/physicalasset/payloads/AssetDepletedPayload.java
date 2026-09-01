package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ASSET_DEPLETED event.
 * Ref: ADR-005
 */
public record AssetDepletedPayload(
    long previousQuantity
) implements DomainEventPayload {}
