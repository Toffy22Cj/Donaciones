package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_SPLIT event.
 * Ref: ADR-005, ADR-008
 */
public record AssetSplitPayload(
    String childAssetId,
    BigDecimal extractedQuantity,
    String unitOfMeasure,
    BigDecimal parentQuantityBefore,
    BigDecimal parentQuantityAfter,
    String statusBeforeSplit,
    String childLocation,
    String childCustodianRef,
    String rootAssetRef
) implements DomainEventPayload {}
