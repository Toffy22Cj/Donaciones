package com.traceability.core.application.saga;

/**
 * Estados del mensaje de outbox (ADR-007/008 Enmienda 1, D3).
 */
public enum OutboxStatus {
    /** En ejecución o en resolución, con reintento programado. */
    PENDING,
    /** Solo la resolución que no pudo completarse: necesita a una persona (salida manual por JMX, D5). */
    QUARANTINED,
    /** {@code execute} tuvo éxito. */
    COMPLETED,
    /** La resolución tuvo éxito (compensado o recuperado hacia delante), o una persona la resolvió a mano. */
    RESOLVED
}
