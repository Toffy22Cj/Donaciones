package com.traceability.contracts;

import java.time.Instant;

/**
 * Represents a deterministic transition fact that occurred during the physical asset lifecycle.
 * Ref: ADR-015
 */
public record TransitionFactDTO(
    String assetRef,
    String fromStatus,
    String toStatus,
    Instant occurredAtFrom,
    Instant occurredAtTo,
    long durationSeconds,
    Long expectedMaximumSeconds,
    boolean anomaly
) {
}
