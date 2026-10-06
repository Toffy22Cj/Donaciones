package com.traceability.convocatoria.application.idempotency;

/**
 * Colisión concurrente sobre el reclamo de un {@code commandId} ({@code DuplicateKey}). No es un error de dominio:
 * se trata como reintento de la transacción completa + lectura del resultado guardado (implementation_plan.md §7.1).
 */
public class CommandClaimCollisionException extends RuntimeException {

    public CommandClaimCollisionException(String commandId, Throwable cause) {
        super("Concurrent claim collision for command " + commandId, cause);
    }
}
