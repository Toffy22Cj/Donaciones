package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class InvalidSplitTargetException extends DomainInvariantViolationException {
    public InvalidSplitTargetException(String message) {
        super(message);
    }
}
