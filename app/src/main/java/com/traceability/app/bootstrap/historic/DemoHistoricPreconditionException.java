package com.traceability.app.bootstrap.historic;

/**
 * La semilla histórica no puede correr sobre esta base: falta el escenario de {@code demo-seed} (su organización y sus
 * cuentas) o las convocatorias históricas ya existen. No crea nada.
 */
public class DemoHistoricPreconditionException extends RuntimeException {
    public DemoHistoricPreconditionException(String message) {
        super(message);
    }
}
