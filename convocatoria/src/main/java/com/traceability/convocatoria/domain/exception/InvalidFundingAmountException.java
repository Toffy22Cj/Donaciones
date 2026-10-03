package com.traceability.convocatoria.domain.exception;

/**
 * El importe a aplicar al ledger debe ser estrictamente positivo (ADR-037 §2.2; condición de fallo nombrada según
 * regla 2.6).
 */
public class InvalidFundingAmountException extends ConvocatoriaDomainException {

    public InvalidFundingAmountException(String message) {
        super(message);
    }
}
