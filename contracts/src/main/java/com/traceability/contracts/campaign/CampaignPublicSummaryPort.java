package com.traceability.contracts.campaign;

import java.util.Optional;

/**
 * Título de una convocatoria y nombre público de su organización, para el seguimiento del donante (ficha R-6, G-1;
 * D-R6-1). Solo lectura. Declarado en contracts para que lo use api; implementado en app.
 */
public interface CampaignPublicSummaryPort {

    /** Vacío si la convocatoria no existe. {@code organizationName} es nulo si la organización no tiene nombre (DD-04). */
    Optional<CampaignPublicSummary> findByCampaignRef(String campaignRef);

    record CampaignPublicSummary(String title, String organizationName) {}
}
