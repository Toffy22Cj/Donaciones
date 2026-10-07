package com.traceability.core.application.port.out;

import java.util.List;

/**
 * Activos de una organización por su génesis {@code ASSET_REGISTERED} (v2 y v3 llevan {@code organizationRef}; los v1
 * no, y no se infiere). Solo lee el event store (P2.7).
 */
public interface AssetDirectoryPort {

    List<String> findAssetIdsByOrganization(String organizationRef, int limit);
}
