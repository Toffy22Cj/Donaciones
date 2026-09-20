package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_REGISTERED event with schemaVersion 2.0.
 * Ref: ADR-029
 */
public record AssetRegisteredV2Payload(
    String assetId,
    String assetType,
    BigDecimal quantity,
    String unitOfMeasure,
    String currentLocation,
    String custodianRef,
    String parentAssetRef,
    String rootAssetRef,
    String allocationId,
    String sourceAllocationId,
    String organizationRef,
    String donorRef
) implements DomainEventPayload {}
