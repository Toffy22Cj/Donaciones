package com.traceability.convocatoria.domain.exception;

/**
 * `title` obligatorio y no vacío (R1, convocatoria-resumen.md §6.15; implementation_plan.md §3.1).
 */
public class CampaignTitleRequiredException extends ConvocatoriaDomainException {

    public CampaignTitleRequiredException(String message) {
        super(message);
    }
}
