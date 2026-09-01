package com.traceability.contracts;

import java.time.Instant;

/**
 * Represents a deterministic financial fact that occurred during the donation lifecycle.
 * Ref: ADR-015
 */
public record FinancialFlagDTO(
    String type,
    String allocationId,
    String refundId,
    boolean causedDeficit,
    long amount,
    Instant occurredAt
) {
}
