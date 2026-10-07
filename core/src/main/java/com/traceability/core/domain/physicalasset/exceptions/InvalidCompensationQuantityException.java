package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

/** La cantidad reintegrada en una compensación no es la extraída en esa división (D-SPLIT S4). */
public class InvalidCompensationQuantityException extends DomainInvariantViolationException {
    public InvalidCompensationQuantityException(String message) {
        super(message);
    }
}
