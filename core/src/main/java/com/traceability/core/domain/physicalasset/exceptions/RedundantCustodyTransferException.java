package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class RedundantCustodyTransferException extends DomainInvariantViolationException {
    public RedundantCustodyTransferException(String message) {
        super(message);
    }
}
