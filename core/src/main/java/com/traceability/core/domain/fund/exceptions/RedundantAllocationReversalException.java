package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;

public class RedundantAllocationReversalException extends DomainInvariantViolationException implements RedundantDomainActionException {
    public RedundantAllocationReversalException(String message) {
        super(message);
    }
}
