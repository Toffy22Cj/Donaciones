package com.traceability.contracts.campaign;

/**
 * Resultado de {@link CampaignInKindEligibilityPort} (ADR-029 Enmienda 1, D3). Cada valor distinto de
 * {@link #ELIGIBLE} corresponde a una excepción nombrada en el consumidor. Hacia fuera, {@link #CAMPAIGN_NOT_FOUND} y
 * {@link #OTHER_ORGANIZATION} deben dar la misma respuesta, para no revelar si un {@code campaignRef} existe en otra
 * organización.
 */
public enum InKindEligibility {
    ELIGIBLE,
    CAMPAIGN_NOT_FOUND,
    OTHER_ORGANIZATION,
    CAMPAIGN_CLOSED,
    IN_KIND_NOT_ACCEPTED
}
