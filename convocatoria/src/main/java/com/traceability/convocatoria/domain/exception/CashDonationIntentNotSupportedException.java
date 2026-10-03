package com.traceability.convocatoria.domain.exception;

/**
 * `CASH` no crea `DonationIntent` en este corte: P5 abierto (Enmienda §5.1, §8; implementation_plan.md §9.1).
 */
public class CashDonationIntentNotSupportedException extends ConvocatoriaDomainException {

    public CashDonationIntentNotSupportedException(String message) {
        super(message);
    }
}
