package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_SPLIT event with schemaVersion 2.0.
 * Ref: ADR-005, ADR-008, ADR-029
 */
public record AssetSplitV2Payload(
    String childAssetId,
    BigDecimal extractedQuantity,
    String unitOfMeasure,
    BigDecimal parentQuantityBefore,
    BigDecimal parentQuantityAfter,
    String statusBeforeSplit,
    String childLocation,
    String childCustodianRef,
    String rootAssetRef,
    String organizationRef,
    String donorRef,
    String donationRef
) implements DomainEventPayload {}
