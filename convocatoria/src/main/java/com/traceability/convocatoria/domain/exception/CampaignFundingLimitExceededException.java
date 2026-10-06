package com.traceability.convocatoria.domain.exception;

/**
 * La aplicación de fondos superaría la meta con `STRICT` o `CLOSE_ON_TARGET` + `REJECT_EXCESS` (ADR-037 §2.2; Enmienda §3.3).
 */
public class CampaignFundingLimitExceededException extends ConvocatoriaDomainException {

    public CampaignFundingLimitExceededException(String message) {
        super(message);
    }
}
