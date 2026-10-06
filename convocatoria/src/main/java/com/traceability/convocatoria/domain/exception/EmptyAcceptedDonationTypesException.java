package com.traceability.convocatoria.domain.exception;

/**
 * `acceptedDonationTypes` nunca está vacío (N1, Enmienda §3.1).
 */
public class EmptyAcceptedDonationTypesException extends ConvocatoriaDomainException {

    public EmptyAcceptedDonationTypesException(String message) {
        super(message);
    }
}
