package com.traceability.core.application.exception;

import com.traceability.contracts.campaign.InKindEligibility;

/**
 * Rechazo del {@code campaignRef} de una donación en especie (Camino B; ADR-029 Enmienda 1, D3). Cada motivo tiene su
 * subclase nombrada (regla 2.6). El mensaje es público: no incluye el motivo cuando revelaría si la convocatoria
 * existe en otra organización. El motivo real queda en {@link #reason()} para el log interno.
 */
public abstract class CampaignNotEligibleForInKindDonationException extends RuntimeException {

    /** Mensaje único para "no existe" y "otra organización": no deben distinguirse hacia fuera. */
    public static final String CAMPAIGN_NOT_AVAILABLE_MESSAGE = "Campaign not available for this in-kind donation";

    private final String campaignRef;
    private final InKindEligibility reason;

    protected CampaignNotEligibleForInKindDonationException(String publicMessage, String campaignRef,
                                                            InKindEligibility reason) {
        super(publicMessage);
        this.campaignRef = campaignRef;
        this.reason = reason;
    }

    public String campaignRef() {
        return campaignRef;
    }

    public InKindEligibility reason() {
        return reason;
    }
}
