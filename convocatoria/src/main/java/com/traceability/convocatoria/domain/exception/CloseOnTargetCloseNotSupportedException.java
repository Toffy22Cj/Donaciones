package com.traceability.convocatoria.domain.exception;

/**
 * La rama `CLOSE_ON_TARGET` + `CLOSE` de la aplicación de fondos no existe en este corte: R4 abierto (implementation_plan.md §8, §14.2).
 */
public class CloseOnTargetCloseNotSupportedException extends ConvocatoriaDomainException {

    public CloseOnTargetCloseNotSupportedException(String message) {
        super(message);
    }
}
