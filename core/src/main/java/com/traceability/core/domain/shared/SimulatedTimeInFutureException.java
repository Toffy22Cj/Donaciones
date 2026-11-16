package com.traceability.core.domain.shared;

/**
 * Se pidió fechar un hecho en el futuro. {@code occurredAt} nunca puede ser posterior a {@code recordedAt}: un
 * evento no puede viajar al futuro.
 */
public class SimulatedTimeInFutureException extends RuntimeException {
    public SimulatedTimeInFutureException(String message) {
        super(message);
    }
}
