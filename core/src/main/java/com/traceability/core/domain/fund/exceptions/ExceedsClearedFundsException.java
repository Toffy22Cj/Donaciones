package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class ExceedsClearedFundsException extends DomainInvariantViolationException {
    public ExceedsClearedFundsException(String message) {
        super(message);
    }
}
