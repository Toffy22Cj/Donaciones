package com.traceability.core.application.port.out;

import java.util.Optional;

public interface ProcessedCommandRepositoryPort {
    void save(String commandId);
    boolean exists(String commandId);
    boolean tryClaim(String commandId);

    /**
     * Reclama {@code commandId} guardando el resultado que lo ganó (barrera de la división, plan B1-bis §2).
     *
     * @return {@code true} si este llamador creó el reclamo
     */
    boolean tryClaim(String commandId, String outcome);

    /** Resultado guardado en el reclamo, o vacío si no existe o se reclamó sin resultado. */
    Optional<String> findOutcome(String commandId);
}
