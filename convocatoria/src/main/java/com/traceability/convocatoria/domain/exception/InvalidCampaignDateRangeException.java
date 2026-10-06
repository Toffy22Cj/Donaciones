package com.traceability.convocatoria.domain.exception;

/**
 * `startDate` y `endDate` obligatorias con `startDate < endDate` (R1; implementation_plan.md §3.1).
 */
public class InvalidCampaignDateRangeException extends ConvocatoriaDomainException {

    public InvalidCampaignDateRangeException(String message) {
        super(message);
    }
}
