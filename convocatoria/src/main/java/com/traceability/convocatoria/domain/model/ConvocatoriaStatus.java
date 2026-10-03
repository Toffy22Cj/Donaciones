package com.traceability.convocatoria.domain.model;

/**
 * Estado persistido de la convocatoria (ADR-037 §2.1); solo `OPEN → CLOSED` (Enmienda §3.4).
 */
public enum ConvocatoriaStatus {
    OPEN,
    CLOSED
}
