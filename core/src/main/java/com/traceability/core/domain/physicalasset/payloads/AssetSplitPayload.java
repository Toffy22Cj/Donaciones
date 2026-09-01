package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ASSET_SPLIT event.
 * Ref: ADR-005, ADR-008
 */
public record AssetSplitPayload(
    String childAssetId,
    long extractedQuantity,
    String unitOfMeasure,
    long parentQuantityBefore,
    long parentQuantityAfter,
    String statusBeforeSplit,
    String childLocation,
    String childCustodianRef,
    String rootAssetRef
) implements DomainEventPayload {}
