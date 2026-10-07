package com.traceability.convocatoria.domain.exception;

/**
 * `targetAmount` debe ser estrictamente positivo (implementation_plan.md §3.1; condición de fallo nombrada según regla 2.6).
 */
public class InvalidTargetAmountException extends ConvocatoriaDomainException {

    public InvalidTargetAmountException(String message) {
        super(message);
    }
}
