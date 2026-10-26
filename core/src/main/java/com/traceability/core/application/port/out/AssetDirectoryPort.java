package com.traceability.core.application.port.out;

import java.util.List;

/**
 * Activos de una organización por su génesis {@code ASSET_REGISTERED} (v2 y v3 llevan {@code organizationRef}; los v1
 * no, y no se infiere). Solo lee el event store (P2.7).
 */
public interface AssetDirectoryPort {

    List<String> findAssetIdsByOrganization(String organizationRef, int limit);

    /**
     * Enmienda 3 de la ficha R-6 (P-E2-2; autorizado por Manuel, reglas-equipo §3.5): los mismos activos, de la génesis
     * más reciente a la más antigua (en un empate, {@code streamId} descendente), como mucho {@code limit}. Solo lo usa
     * G-8; el método de arriba no cambia.
     */
    List<String> findLatestAssetIdsByOrganization(String organizationRef, int limit);
}
