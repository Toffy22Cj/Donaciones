package com.traceability.core.application.port.out;

import java.time.Instant;

public record AssetTransitionReadModel(
        String eventType,
        String status,
        String custodian,
        String location,
        Instant timestamp,
        /** Ficha R-6, G-3 (P-R6C-2): {@code sequence} del evento en el Event Store, para cruzarlo en el servidor. Nunca sale por HTTP (DD-76). */
        long sequence,
        /** ADR-029 Enmienda 2, D5: la zona sellada del paso; {@code null} en los pasos anteriores (respaldo con {@code location}). */
        String zoneName
) {
    /** Un paso sin zona sellada. */
    public AssetTransitionReadModel(String eventType, String status, String custodian, String location, Instant timestamp,
                                    long sequence) {
        this(eventType, status, custodian, location, timestamp, sequence, null);
    }
}
