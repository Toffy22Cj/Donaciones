package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_DEPLETED event.
 * Ref: ADR-005
 */
public record AssetDepletedPayload(
    BigDecimal previousQuantity
) implements DomainEventPayload {}
