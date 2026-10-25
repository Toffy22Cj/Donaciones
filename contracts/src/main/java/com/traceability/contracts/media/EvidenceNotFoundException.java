package com.traceability.contracts.media;

/**
 * ADR-051 F3: la foto de evidencia no existe, no es de evidencia, es de otra organización que la del activo o está
 * borrada. El mismo error para todos esos casos, para no revelar cuál es. → 400.
 */
public class EvidenceNotFoundException extends RuntimeException {

    public EvidenceNotFoundException(String message) {
        super(message);
    }
}
