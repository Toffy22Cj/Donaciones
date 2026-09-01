package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class DuplicateRefundException extends DomainInvariantViolationException {
    public DuplicateRefundException(String message) {
        super(message);
    }
}
