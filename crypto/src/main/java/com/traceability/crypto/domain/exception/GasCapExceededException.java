package com.traceability.crypto.domain.exception;

public class GasCapExceededException extends RuntimeException {
    public GasCapExceededException(String message) {
        super(message);
    }
}
