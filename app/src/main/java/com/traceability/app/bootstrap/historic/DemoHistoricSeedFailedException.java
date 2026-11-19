package com.traceability.app.bootstrap.historic;

/**
 * Un paso de la semilla histórica no dio el resultado que el escenario necesita (por ejemplo, unos fondos que no se
 * aplicaron). La semilla se detiene: nunca compensa con inserciones directas.
 */
public class DemoHistoricSeedFailedException extends RuntimeException {
    public DemoHistoricSeedFailedException(String message) {
        super(message);
    }
}
