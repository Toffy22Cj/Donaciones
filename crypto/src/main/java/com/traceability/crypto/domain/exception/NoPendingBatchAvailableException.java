package com.traceability.crypto.domain.exception;

public class NoPendingBatchAvailableException extends RuntimeException {
    public NoPendingBatchAvailableException(String message) {
        super(message);
    }
}
