package com.traceability.convocatoria.domain.exception;

/**
 * La aplicación de fondos consume una `DonationIntent` ya `CONFIRMED` (F-1, F-2); una intención en otro estado
 * (`PENDING`, `FUNDING_REJECTED`, …) no se aplica.
 */
public class DonationIntentNotConfirmedException extends ConvocatoriaDomainException {

    public DonationIntentNotConfirmedException(String message) {
        super(message);
    }
}
