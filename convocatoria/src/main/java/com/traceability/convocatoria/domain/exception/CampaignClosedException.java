package com.traceability.convocatoria.domain.exception;

/**
 * Rechazo de nuevas `DonationIntent` con `status ≠ OPEN` (ADR-037 §2.6; Enmienda §3.4).
 */
public class CampaignClosedException extends ConvocatoriaDomainException {

    public CampaignClosedException(String message) {
        super(message);
    }
}
