package com.traceability.core.domain.physicalasset.exceptions;

/**
 * Domain Exception thrown when a write command is executed on a PhysicalAsset
 * that has no associated Organization (v1 legacy asset, organizationRef ==
 * null).
 * Ref: ADR-029
 */
public class PhysicalAssetNotAssociatedToOrganizationException extends RuntimeException {
    public PhysicalAssetNotAssociatedToOrganizationException(String message) {
        super(message);
    }
}