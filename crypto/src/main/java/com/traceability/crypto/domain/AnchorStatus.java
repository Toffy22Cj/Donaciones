package com.traceability.crypto.domain;

public enum AnchorStatus {
    COLLECTING,
    PENDING,
    SUBMITTING,
    SUBMITTED,
    ANCHORED,
    STUCK,
    FAILED,
    ANCHOR_MISMATCH,
    /** Enmienda 1 de ADR-039 §2.3: agotó el tope de recuperación de COLLECTING; terminal hasta RETRY o RELEASE (JMX). */
    COLLECTING_FAILED,
    /** Enmienda 1 de ADR-039 §2.3: liberado por RELEASE; conserva su cobertura como registro y sus eventos vuelven a ser reclamables. */
    RELEASED
}
