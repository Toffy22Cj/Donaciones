package com.traceability.core.application.exception;

/**
 * ADR-051 F3 (Tarea 4; criterio 6): el {@code evidenceRef} de una entrega nueva no tiene el formato
 * {@code ^sha256:[0-9a-f]{64}$}. Lo lanza {@code PhysicalAssetCommandService.deliverAsset} antes de reclamar el comando
 * (P-T4-1), así que un formato inválido no consume el {@code Command-Id}. → 400.
 */
public class InvalidEvidenceRefException extends RuntimeException {

    public InvalidEvidenceRefException(String message) {
        super(message);
    }
}
