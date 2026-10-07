package com.traceability.convocatoria.domain.exception;

/**
 * La moneda de la intención difiere de la de la convocatoria (R1; implementation_plan.md §9.1).
 */
public class DonationCurrencyMismatchException extends ConvocatoriaDomainException {

    public DonationCurrencyMismatchException(String message) {
        super(message);
    }
}
