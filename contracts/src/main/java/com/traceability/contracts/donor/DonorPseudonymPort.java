package com.traceability.contracts.donor;

import java.util.Optional;

/**
 * Seudónimo de donante de una cuenta (ADR-048, opción (C), APROBADO por Carlos el 2026-10-07). La relación cuenta ↔
 * seudónimo es el dato más sensible del sistema (ADR-048 §7): este puerto solo va de una cuenta a su seudónimo, para
 * que {@code app} calcule el {@code donorRef}. No hay operación inversa ni listado.
 */
public interface DonorPseudonymPort {

    /** Obtiene o crea, de forma idempotente, el seudónimo (UUID aleatorio) de la cuenta. */
    String pseudonymFor(String accountId);

    /** El seudónimo de la cuenta si ya existe (lectura del historial: no crea nada). */
    Optional<String> existingPseudonymFor(String accountId);
}
