package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;

public class RedundantCustodyTransferException extends DomainInvariantViolationException implements RedundantDomainActionException {
    public RedundantCustodyTransferException(String message) {
        super(message);
    }
}
