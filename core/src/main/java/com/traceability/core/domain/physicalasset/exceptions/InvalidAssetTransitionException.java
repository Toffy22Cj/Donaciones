package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class InvalidAssetTransitionException extends DomainInvariantViolationException {
    public InvalidAssetTransitionException(String message) {
        super(message);
    }
}
