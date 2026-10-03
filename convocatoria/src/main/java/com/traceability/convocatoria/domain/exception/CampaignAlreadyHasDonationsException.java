package com.traceability.convocatoria.domain.exception;

/**
 * Edición directa solo antes de la primera donación (Enmienda §3.2; H1, implementation_plan.md §5).
 */
public class CampaignAlreadyHasDonationsException extends ConvocatoriaDomainException {

    public CampaignAlreadyHasDonationsException(String message) {
        super(message);
    }
}
