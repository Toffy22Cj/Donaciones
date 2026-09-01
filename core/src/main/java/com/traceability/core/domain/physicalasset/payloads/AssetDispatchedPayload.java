package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ASSET_DISPATCHED event.
 * Ref: ADR-014
 */
public record AssetDispatchedPayload(
    String carrierRef,
    String previousLocation
) implements DomainEventPayload {}
