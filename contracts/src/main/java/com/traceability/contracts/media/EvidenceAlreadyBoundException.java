package com.traceability.contracts.media;

/** ADR-051 F3: la foto de evidencia ya está vinculada a otro activo ("una foto, un activo"). → 409. */
public class EvidenceAlreadyBoundException extends RuntimeException {

    public EvidenceAlreadyBoundException(String message) {
        super(message);
    }
}
