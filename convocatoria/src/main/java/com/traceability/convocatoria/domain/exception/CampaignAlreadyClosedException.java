package com.traceability.convocatoria.domain.exception;

/**
 * El cierre manual solo existe desde `OPEN` (Enmienda §3.4; implementation_plan.md §10).
 */
public class CampaignAlreadyClosedException extends ConvocatoriaDomainException {

    public CampaignAlreadyClosedException(String message) {
        super(message);
    }
}
