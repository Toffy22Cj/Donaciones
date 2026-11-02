package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * [Plan] Payload de ASSET_REGISTERED con schemaVersion 4.0: la 3.0 + la zona DIVIPOLA sellada ({@code municipalityCode},
 * {@code zoneName} y {@code catalogVersion}; los tres {@code null} si no hay zona, E-1). Toda escritura nueva usa la 4.0;
 * la 1.0, la 2.0 y la 3.0 solo se leen y no cambian. Ref: ADR-029, Enmienda 2 (D1, D2 y D4)
 */
public record AssetRegisteredV4Payload(
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
    String campaignRef,
    String municipalityCode,
    String zoneName,
    String catalogVersion
) implements DomainEventPayload {}
