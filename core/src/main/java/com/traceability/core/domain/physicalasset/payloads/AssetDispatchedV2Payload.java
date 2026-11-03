package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * [Plan] Payload de ASSET_DISPATCHED con schemaVersion 2.0: la 1.0 + la zona del paso anterior, que el sistema copia del
 * estado del activo; el empleado no manda nada nuevo (P2 de la ficha). Los tres campos son {@code null} si el activo no
 * tiene zona sellada (E-1). Ref: ADR-029, Enmienda 2 (D1 y D2)
 */
public record AssetDispatchedV2Payload(
    String carrierRef,
    String previousLocation,
    String municipalityCode,
    String zoneName,
    String catalogVersion
) implements DomainEventPayload {}
