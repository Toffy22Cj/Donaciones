package com.traceability.core.domain.fund.exceptions;

/**
 * Domain Exception thrown when an action is performed on a Fund that has no associated Organization.
 * Ref: ADR-028
 */
public class FundNotAssociatedToOrganizationException extends RuntimeException {
    public FundNotAssociatedToOrganizationException(String message) {
        super(message);
    }
}
