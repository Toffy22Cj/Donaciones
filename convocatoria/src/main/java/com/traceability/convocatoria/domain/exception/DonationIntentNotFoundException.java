package com.traceability.convocatoria.domain.exception;

/**
 * Intención inexistente al intentar la transición `PENDING → CONFIRMED` (Enmienda §5.3; implementation_plan.md §9.2).
 */
public class DonationIntentNotFoundException extends ConvocatoriaDomainException {

    public DonationIntentNotFoundException(String message) {
        super(message);
    }
}
