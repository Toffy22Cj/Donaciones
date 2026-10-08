package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload for ASSET_REGISTERED event with schemaVersion 3.0: la 2.0 + {@code campaignRef} (puede ser {@code null} = sin convocatoria).
 * Toda escritura nueva usa la 3.0; la 1.0 y la 2.0 solo se leen. Ref: ADR-029, Enmienda 1 (D1)
 */
public record AssetRegisteredV3Payload(
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
    String donorRef,
    String donationRef,
    String campaignRef
) implements DomainEventPayload {}
