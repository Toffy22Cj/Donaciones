package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

import java.time.Instant;

/**
 * [Plan] Payload de ASSET_DELIVERED con schemaVersion 2.0: la 1.0 + la zona DIVIPOLA del comando, sellada (la zona
 * pública de la entrega es el municipio, D-4.7). El texto libre ({@code locationRef}) se conserva como dato privado.
 * Ref: ADR-014; ADR-029, Enmienda 2 (D1 y D2)
 */
public record AssetDeliveredV2Payload(
    String finalCustodianRef,
    String beneficiaryRef,
    String locationRef,
    String evidenceRef,
    Instant deliveredAt,
    String municipalityCode,
    String zoneName,
    String catalogVersion
) implements DomainEventPayload {}
