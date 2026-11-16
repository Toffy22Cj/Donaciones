package com.traceability.app.bootstrap.historic;

/**
 * El perfil {@code demo-historico} se activó sin el perfil {@code dev}. Fechar hechos en el pasado solo se admite en
 * una base local de demostración: fuera de {@code dev}, la aplicación no arranca (como ADR-051 F1 con el
 * almacenamiento local).
 */
public class DemoHistoricProfileNotAllowedException extends RuntimeException {
    public DemoHistoricProfileNotAllowedException(String message) {
        super(message);
    }
}
