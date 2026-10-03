package com.traceability.convocatoria.domain.exception;

/**
 * `MONETARY` no aceptado en la versión vigente (N6, Enmienda §5.1).
 */
public class DonationTypeNotAcceptedException extends ConvocatoriaDomainException {

    public DonationTypeNotAcceptedException(String message) {
        super(message);
    }
}
