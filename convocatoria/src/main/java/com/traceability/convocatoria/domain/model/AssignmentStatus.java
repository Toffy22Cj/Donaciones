package com.traceability.convocatoria.domain.model;

/**
 * Estado de una asignación; `REMOVED` es histórico y no se reactiva (ADR-037 §2.4, §7).
 */
public enum AssignmentStatus {
    ACTIVE,
    REMOVED
}
