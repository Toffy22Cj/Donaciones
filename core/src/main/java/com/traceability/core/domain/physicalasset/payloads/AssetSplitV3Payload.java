package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_SPLIT event with schemaVersion 3.0: la 2.0 + el {@code campaignRef} del padre, que hereda el hijo
 * (puede ser {@code null}). Ref: ADR-005, ADR-008, ADR-029, Enmienda 1 (D1, D4)
 */
public record AssetSplitV3Payload(
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
    String donationRef,
    String campaignRef
) implements DomainEventPayload {}
