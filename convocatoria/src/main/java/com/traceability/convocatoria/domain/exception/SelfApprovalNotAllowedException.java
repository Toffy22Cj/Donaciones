package com.traceability.convocatoria.domain.exception;

/** Quien pidió el cambio no lo aprueba (Enmienda 1 de ADR-037, §3.2: autoasignación ≠ autoaprobación). */
public class SelfApprovalNotAllowedException extends ConvocatoriaDomainException {
    public SelfApprovalNotAllowedException(String message) {
        super(message);
    }
}
