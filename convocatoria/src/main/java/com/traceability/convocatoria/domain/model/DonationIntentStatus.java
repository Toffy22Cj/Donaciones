package com.traceability.convocatoria.domain.model;

/**
 * Estados de `DonationIntent` (ADR-037 §2.6). En este corte se implementan `PENDING → CONFIRMED` y
 * `CONFIRMED → FUNDING_REJECTED`; las transiciones a `FAILED` y `EXPIRED_UNKNOWN` pertenecen al webhook (P3).
 * `CONFIRMED` significa solo que la intención se confirmó válidamente, no que sus fondos estén aplicados (F-1, F-2).
 * `FUNDING_REJECTED` es terminal y significa exclusivamente que la intención estaba `CONFIRMED` y el intento de
 * aplicación financiera fue rechazado de forma permanente; no reutiliza `FAILED`, reservado al fallo del pago
 * (ADR-037 Enmienda 2 §4).
 */
public enum DonationIntentStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED_UNKNOWN,
    FUNDING_REJECTED
}
