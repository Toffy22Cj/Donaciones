package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_SPLIT_COMPENSATED event.
 * Ref: ADR-008, ADR-009
 */
public record AssetSplitCompensatedPayload(
    String childAssetId,
    BigDecimal reintegratedQuantity
) implements DomainEventPayload {}
