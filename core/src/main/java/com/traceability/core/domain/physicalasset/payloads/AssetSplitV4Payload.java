package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * [Plan] Payload de ASSET_SPLIT con schemaVersion 4.0: la 3.0 + la zona sellada del padre, que hereda el hijo (P3 de la
 * ficha; los tres campos {@code null} si el padre no tiene zona, E-1). Ref: ADR-029, Enmienda 2 (D1, D2 y D4)
 */
public record AssetSplitV4Payload(
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
    String campaignRef,
    String municipalityCode,
    String zoneName,
    String catalogVersion
) implements DomainEventPayload {}
