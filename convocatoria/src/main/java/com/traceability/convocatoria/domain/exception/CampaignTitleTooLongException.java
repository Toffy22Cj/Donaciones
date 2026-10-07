package com.traceability.convocatoria.domain.exception;

/**
 * `title` supera la longitud máxima de implementación (R1; implementation_plan.md §3.1, longitud reportada en §16).
 */
public class CampaignTitleTooLongException extends ConvocatoriaDomainException {

    public CampaignTitleTooLongException(String message) {
        super(message);
    }
}
