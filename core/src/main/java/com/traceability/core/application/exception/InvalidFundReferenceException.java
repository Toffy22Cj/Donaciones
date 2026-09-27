package com.traceability.core.application.exception;

public class InvalidFundReferenceException extends RuntimeException {
    public InvalidFundReferenceException(String message) {
        super(message);
    }
}
