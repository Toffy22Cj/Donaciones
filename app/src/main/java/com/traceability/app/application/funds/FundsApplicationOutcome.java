package com.traceability.app.application.funds;

/**
 * Resultado de un intento de aplicación de fondos (taxonomía de ADR-045 §2.3).
 */
public enum FundsApplicationOutcome {
    /** Reclamo, ledger y génesis del {@code Fund} confirmados en la Tx 2. */
    APPLIED,
    /** La barrera ya estaba reclamada (otra instancia o el disparo inmediato): no-op, no cuenta como intento. */
    ALREADY_APPLIED,
    /** La intención no está {@code CONFIRMED} (p. ej. ya {@code FUNDING_REJECTED}): no-op, no cuenta como intento. */
    NOT_CONFIRMED,
    /** Rechazo permanente de negocio: {@code CONFIRMED → FUNDING_REJECTED} con motivo y fecha (C2). */
    FUNDING_REJECTED,
    /** Fallo reintentable contado; la intención sigue en la cola. */
    RETRYABLE_FAILURE,
    /** Anomalía, excepción no clasificada o reintentos agotados: fuera de la cola hasta la salida manual. */
    QUARANTINED
}
