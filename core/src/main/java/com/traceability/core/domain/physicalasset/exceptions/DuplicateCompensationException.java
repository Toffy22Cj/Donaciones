package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class DuplicateCompensationException extends DomainInvariantViolationException {
    public DuplicateCompensationException(String message) {
        super(message);
    }
}
