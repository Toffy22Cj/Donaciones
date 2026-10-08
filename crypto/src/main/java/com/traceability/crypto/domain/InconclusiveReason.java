package com.traceability.crypto.domain;

/** Por qué una verificación es {@code INCONCLUSIVE} (Enmienda 1 de ADR-039 §2.2.4, aprobada por Carlos el 2026-10-08). */
public enum InconclusiveReason {
    /** El lote aún no está anclado. */
    NOT_ANCHORED,
    /** Algún evento anterior al corte de {@code 0579f41} no coincide con ninguna forma canónica conocida. */
    CANONICAL_FORM_UNKNOWN
}
