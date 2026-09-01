package com.traceability.core.domain.physicalasset.exceptions;

import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;

public class AssetTerminalStateException extends DomainInvariantViolationException {
    public AssetTerminalStateException(String message) {
        super(message);
    }
}
