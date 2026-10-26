package com.traceability.app.application.media;

/**
 * ADR-051 F6 (Tarea 5): el activo no tiene una evidencia que servir (sin entrega, entregado con texto libre heredado,
 * sin metadato o sin bytes). El mismo 404 en todos los casos, como el logo y la portada.
 */
public class DeliveryEvidenceUnavailableException extends RuntimeException {
    public DeliveryEvidenceUnavailableException() {
        super("Delivery evidence NotFound");
    }
}
