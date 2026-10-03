package com.traceability.convocatoria.domain.exception;

/**
 * Una intención `BANK_TRANSFER` vencida no puede confirmarse (Enmienda §5.2; implementation_plan.md §9.2).
 */
public class DonationIntentExpiredException extends ConvocatoriaDomainException {

    public DonationIntentExpiredException(String message) {
        super(message);
    }
}
