package com.traceability.contracts;

/**
 * Estado de anclaje de un paso del historial del donante, en los cuatro valores de la ficha R-6 (§5, G-3; C-4). Cubre
 * los diez estados del lote ({@code AnchorStatus} de {@code crypto}). Declarado en contracts para que la respuesta no
 * pueda llevar un quinto valor sin cambiar el contrato (plan del Bloque C, P-R6C-1).
 */
public enum TrackingAnchorStatus {
    /** Lote {@code ANCHORED}: anclado y confirmado. */
    ANCHORED,
    /** Lote {@code COLLECTING}, {@code PENDING}, {@code SUBMITTING}, {@code SUBMITTED} o {@code STUCK}: en proceso. */
    IN_BATCH,
    /** Sin lote, o lote {@code RELEASED}: todavía no está en ningún lote. */
    PENDING,
    /**
     * Lote {@code FAILED}, {@code COLLECTING_FAILED} o {@code ANCHOR_MISMATCH}; o, con el lote {@code ANCHORED}, un
     * evento cuyo hash ya no es la hoja que se ancló (ficha R-6, Enmienda 1, E1.2).
     */
    UNDER_REVIEW
}
