package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class InsufficientAvailableFundsException extends DomainInvariantViolationException {
    public InsufficientAvailableFundsException(String message) {
        super(message);
    }
}
