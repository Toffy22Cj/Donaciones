package com.traceability.convocatoria.domain.model;

/**
 * Comportamiento al alcanzar la meta con `CLOSE_ON_TARGET` (Enmienda §3.1, §3.3).
 */
public enum OnTargetReached {
    CLOSE,
    REJECT_EXCESS,
    ACCEPT_EXCESS
}
