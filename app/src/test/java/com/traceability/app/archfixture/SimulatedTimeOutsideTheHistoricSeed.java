package com.traceability.app.archfixture;

import com.traceability.core.domain.shared.EventTime;

import java.time.Instant;

/**
 * Fixture de test: una clase <b>fuera</b> de la semilla histórica que fecha un hecho en el pasado y arma la fuente de
 * tiempo. La regla de {@code SimulatedTimeArchitectureTest} tiene que detectar las dos cosas. No es código de
 * producción.
 */
public class SimulatedTimeOutsideTheHistoricSeed {

    public String backdate() {
        return EventTime.callAt(Instant.parse("2026-01-01T00:00:00Z"), () -> "hecho fechado en el pasado");
    }

    public void arm() {
        EventTime.arm().close();
    }
}
