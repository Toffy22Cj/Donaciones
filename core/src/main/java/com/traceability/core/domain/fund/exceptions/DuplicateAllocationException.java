package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class DuplicateAllocationException extends DomainInvariantViolationException {
    public DuplicateAllocationException(String message) {
        super(message);
    }
}
