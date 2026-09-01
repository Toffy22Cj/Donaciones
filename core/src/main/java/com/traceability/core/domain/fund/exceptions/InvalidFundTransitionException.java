package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class InvalidFundTransitionException extends DomainInvariantViolationException {
    public InvalidFundTransitionException(String message) {
        super(message);
    }
}
