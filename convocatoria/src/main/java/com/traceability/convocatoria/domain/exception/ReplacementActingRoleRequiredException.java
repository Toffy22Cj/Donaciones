package com.traceability.convocatoria.domain.exception;

/**
 * Con reemplazo, `RemoveResponsible` exige el `actingRole` del reemplazo (decisión humana del 2026-10-01, G2; Enmienda §4.2).
 */
public class ReplacementActingRoleRequiredException extends ConvocatoriaDomainException {

    public ReplacementActingRoleRequiredException(String message) {
        super(message);
    }
}
