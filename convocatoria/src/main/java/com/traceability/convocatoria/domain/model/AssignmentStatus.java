package com.traceability.convocatoria.domain.model;

/**
 * Estado de una asignación; `REMOVED` es histórico y no se reactiva (ADR-037 §2.4, §7).
 */
public enum AssignmentStatus {
    ACTIVE,
    REMOVED,
    /**
     * La convocatoria se cerró con la asignación activa (Carlos, 2026-10-08, D-06): queda como historial, ya no cuenta
     * como responsabilidad activa y libera al {@code EMPLOYEE} para otra convocatoria. {@code removedAt} es el cierre.
     */
    HISTORICAL
}
