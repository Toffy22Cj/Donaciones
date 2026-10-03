package com.traceability.convocatoria.domain.exception;

/**
 * Una convocatoria solo `IN_KIND` no tiene `targetAmount`, `targetPolicy` ni `currency` (N2, Enmienda §3.1; R1).
 */
public class MonetaryTermsWithoutMonetaryDonationTypeException extends ConvocatoriaDomainException {

    public MonetaryTermsWithoutMonetaryDonationTypeException(String message) {
        super(message);
    }
}
