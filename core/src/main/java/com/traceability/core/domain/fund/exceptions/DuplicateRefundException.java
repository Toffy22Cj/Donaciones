package com.traceability.core.domain.fund.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;

public class DuplicateRefundException extends DomainInvariantViolationException implements RedundantDomainActionException {
    public DuplicateRefundException(String message) {
        super(message);
    }
}
