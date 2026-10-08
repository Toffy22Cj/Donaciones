package com.traceability.convocatoria.domain.exception;

/**
 * Proveedor `SIMULATED` con la política `allowSimulatedPayments` desactivada: segunda barrera de E3-Q1 (Enmienda 3 de ADR-037, D2).
 */
public class SimulatedPaymentsNotAllowedException extends ConvocatoriaDomainException {

    public SimulatedPaymentsNotAllowedException(String message) {
        super(message);
    }
}
