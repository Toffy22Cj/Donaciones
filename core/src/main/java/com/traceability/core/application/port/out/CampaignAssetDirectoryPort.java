package com.traceability.core.application.port.out;

import java.util.List;

/**
 * Activos de una convocatoria, por su génesis {@code ASSET_REGISTERED} 3.0 en el event store (plan B5, DD-33):
 * incluye el Camino A, el Camino B y los hijos de una división, que heredan el {@code campaignRef}. Los activos sin
 * convocatoria (v1/v2) nunca aparecen: no se infiere (ADR-029 Enmienda 1).
 */
public interface CampaignAssetDirectoryPort {

    List<String> findAssetIdsByCampaign(String campaignRef);
}
