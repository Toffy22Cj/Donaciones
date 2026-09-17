package com.traceability.core.domain.fund.exceptions;

/**
 * Domain Exception thrown when a Fund genesis validation fails (e.g., missing mandatory attributes).
 * Ref: ADR-028, Rule #4
 */
public class InvalidFundGenesisException extends RuntimeException {
    public InvalidFundGenesisException(String message) {
        super(message);
    }
}
