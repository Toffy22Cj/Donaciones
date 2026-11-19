package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload de ASSET_RECEIVED con schemaVersion 3.0: la 2.0 + {@code evidenceRef}, la huella ({@code sha256:}) de la foto
 * opcional de la recepción; {@code null} sin foto. Toda escritura nueva usa la 3.0; la 1.0 y la 2.0 solo se leen y no
 * cambian. Ref: ADR-051, Enmienda 2 (E2-1 y E2-2)
 */
public record AssetReceivedV3Payload(
    String facilityLocation,
    String receiverRef,
    String municipalityCode,
    String zoneName,
    String catalogVersion,
    String evidenceRef
) implements DomainEventPayload {}
