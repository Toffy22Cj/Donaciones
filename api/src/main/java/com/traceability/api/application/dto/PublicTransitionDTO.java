package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PublicTransitionDTO(
        String eventType,
        String timestamp,
        String locationZone,
        PublicCustodianCategory custodianCategory,
        String status,
        /** Ficha R-6, G-3: {@code ANCHORED}, {@code IN_BATCH}, {@code PENDING} o {@code UNDER_REVIEW}. Siempre. */
        String anchorStatus,
        /** Ficha R-6, Enmienda 1, E1.2: el del Event Store. Falta solo si el paso no tiene evento (P-C-4). */
        @JsonInclude(JsonInclude.Include.NON_NULL) String eventHash,
        /** Ficha R-6, Enmienda 1, E1.2: solo con el lote {@code ANCHORED} y sus hojas guardadas. */
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicAnchorProofDTO anchorProof,
        /**
         * Enmienda 1 de ADR-021-D, E1 (ADR-051, Tarea 5): solo en el paso de la entrega; {@code true} si el evento lleva
         * un {@code evidenceRef} no vacío.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean evidenceRegistered,
        /** E1 y E2: solo si el {@code evidenceRef} es una huella {@code sha256:}; el texto libre heredado nunca sale. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceRef
) {
}
