package com.traceability.api.application.dto;

public record PublicTransitionDTO(
        String eventType,
        String timestamp,
        String locationZone,
        PublicCustodianCategory custodianCategory,
        String status
) {
}
