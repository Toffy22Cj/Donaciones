package com.traceability.core.application.authorization;

public class CrossOrganizationAccessException extends RuntimeException {
    public CrossOrganizationAccessException(String message) {
        super(message);
    }
}