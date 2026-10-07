package com.traceability.core.domain.fund;

/**
 * Opaque Value Object representing a reference to an Organization.
 * Ref: ADR-028
 */
public record OrganizationRef(String value) {
    public OrganizationRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OrganizationRef cannot be null or blank");
        }
    }
}
