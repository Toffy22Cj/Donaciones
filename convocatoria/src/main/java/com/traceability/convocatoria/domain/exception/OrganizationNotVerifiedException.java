package com.traceability.convocatoria.domain.exception;

/**
 * La organización no está `VERIFIED` (ADR-037 §2.6, §5; X1, implementation_plan.md §11).
 */
public class OrganizationNotVerifiedException extends ConvocatoriaDomainException {

    public OrganizationNotVerifiedException(String message) {
        super(message);
    }
}
