package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * [Plan] Payload de ASSET_RECEIVED con schemaVersion 2.0: la 1.0 + la zona DIVIPOLA del comando, sellada. El texto
 * libre ({@code facilityLocation}) se conserva como dato privado. Ref: ADR-029, Enmienda 2 (D1 y D2)
 */
public record AssetReceivedV2Payload(
    String facilityLocation,
    String receiverRef,
    String municipalityCode,
    String zoneName,
    String catalogVersion
) implements DomainEventPayload {}
