package com.traceability.convocatoria.domain.exception;

/**
 * Raíz de las excepciones nombradas del módulo (regla 2.6; implementation_plan.md §3.6).
 * Una excepción de dominio nunca se reintenta (implementation_plan.md §4.4, §13.1).
 */
public abstract class ConvocatoriaDomainException extends RuntimeException {

    protected ConvocatoriaDomainException(String message) {
        super(message);
    }
}
