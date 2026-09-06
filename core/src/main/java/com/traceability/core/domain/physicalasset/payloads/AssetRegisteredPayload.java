package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_REGISTERED event.
 * Ref: ADR-002, ADR-014
 */
public record AssetRegisteredPayload(
    String assetId,
    String assetType,
    BigDecimal quantity,
    String unitOfMeasure,
    String currentLocation,
    String custodianRef,
    String parentAssetRef,
    String rootAssetRef,
    String allocationId,
    String sourceAllocationId
) implements DomainEventPayload {}
