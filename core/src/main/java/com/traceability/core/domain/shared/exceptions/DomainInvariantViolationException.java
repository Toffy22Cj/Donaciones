package com.traceability.core.domain.shared.exceptions;

/**
 * Base exception for all business invariant violations.
 * 
 * Ref: ADR-013 (Validación de reglas de negocio en el dominio puro)
 */
public abstract class DomainInvariantViolationException extends RuntimeException {
    protected DomainInvariantViolationException(String message) {
        super(message);
    }
}
