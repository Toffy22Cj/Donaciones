package com.traceability.app.application.media;

/** ADR-051 F6 y F7: la evidencia de entrega se retiró por la plataforma; la ruta responde 410 {@code EvidenceRemoved}. */
public class EvidenceRemovedException extends RuntimeException {
    public EvidenceRemovedException() {
        super("EvidenceRemoved");
    }
}
