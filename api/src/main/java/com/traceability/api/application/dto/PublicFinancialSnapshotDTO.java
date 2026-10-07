package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PublicFinancialSnapshotDTO(
    @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,
    long refundedAmount
) {
}
