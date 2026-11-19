package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload de ASSET_DISPATCHED con schemaVersion 3.0: la 2.0 + {@code evidenceRef}, la huella ({@code sha256:}) de la foto
 * opcional del despacho; {@code null} sin foto. Toda escritura nueva usa la 3.0; la 1.0 y la 2.0 solo se leen y no
 * cambian. Ref: ADR-051, Enmienda 2 (E2-1 y E2-2)
 */
public record AssetDispatchedV3Payload(
    String carrierRef,
    String previousLocation,
    String municipalityCode,
    String zoneName,
    String catalogVersion,
    String evidenceRef
) implements DomainEventPayload {}
