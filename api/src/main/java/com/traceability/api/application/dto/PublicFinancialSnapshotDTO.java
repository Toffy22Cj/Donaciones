package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code firstClearedAt} y {@code lastClearedAt}: ficha R-6, G-2. El {@code occurredAt} del primer y del último
 * {@code FUNDS_CLEARED} del fondo por {@code sequence}, en ISO-8601 UTC; faltan si no hay liquidaciones.
 */
public record PublicFinancialSnapshotDTO(
    @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,
    long refundedAmount,
    @JsonInclude(JsonInclude.Include.NON_NULL) String firstClearedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) String lastClearedAt
) {
}
