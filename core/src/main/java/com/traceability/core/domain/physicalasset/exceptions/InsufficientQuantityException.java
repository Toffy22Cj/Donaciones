package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class InsufficientQuantityException extends DomainInvariantViolationException {
    public InsufficientQuantityException(String message) {
        super(message);
    }
}
