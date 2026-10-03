package com.traceability.convocatoria.domain.exception;

/**
 * Meta, política y moneda no se editan: no existe operación de cambio de meta/política (implementation_plan.md §3.1; resumen §2).
 */
public class MonetaryTermsChangeNotSupportedException extends ConvocatoriaDomainException {

    public MonetaryTermsChangeNotSupportedException(String message) {
        super(message);
    }
}
