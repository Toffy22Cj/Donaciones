package com.traceability.core.application.exception;

/**
 * ADR-059 (pregunta 2, respondida por Carlos el 2026-11-19): un evento nuevo no puede guardarse si {@code occurredAt}
 * (cuándo ocurrió el hecho, lo fija el agregado) es posterior a {@code recordedAt} (cuándo lo registra el almacén) o si
 * falta. Es un fallo determinista, no de concurrencia: nunca se reintenta, y no se guarda ningún evento del lote.
 */
public class EventOccurredAfterRecordedException extends RuntimeException {
    public EventOccurredAfterRecordedException(String message) {
        super(message);
    }
}
