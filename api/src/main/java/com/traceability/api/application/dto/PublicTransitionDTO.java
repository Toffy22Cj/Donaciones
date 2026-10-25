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
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicAnchorProofDTO anchorProof
) {
}
