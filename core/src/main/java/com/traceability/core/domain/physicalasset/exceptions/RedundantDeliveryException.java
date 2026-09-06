package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;

public class RedundantDeliveryException extends DomainInvariantViolationException implements RedundantDomainActionException {
    public RedundantDeliveryException(String message) {
        super(message);
    }
}
