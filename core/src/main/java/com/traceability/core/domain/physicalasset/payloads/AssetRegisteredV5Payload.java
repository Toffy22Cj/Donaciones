package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.math.BigDecimal;

/**
 * Payload de ASSET_REGISTERED con schemaVersion 5.0: la 4.0 + {@code evidenceRef}, la huella ({@code sha256:}) de la foto
 * opcional del registro; {@code null} sin foto y en el hijo de una división. Toda escritura nueva usa la 5.0, con o sin
 * foto; la 1.0 a la 4.0 solo se leen y no cambian (añadir el campo a la 4.0 cambiaría el hash de los eventos ya guardados).
 * Ref: ADR-051, Enmienda 2 (E2-1 y E2-2)
 */
public record AssetRegisteredV5Payload(
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
    String catalogVersion,
    String evidenceRef
) implements DomainEventPayload {}
