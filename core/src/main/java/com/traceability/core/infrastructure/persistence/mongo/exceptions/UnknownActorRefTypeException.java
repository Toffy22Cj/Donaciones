package com.traceability.core.infrastructure.persistence.mongo.exceptions;

public class UnknownActorRefTypeException extends RuntimeException {
    public UnknownActorRefTypeException(String message) {
        super(message);
    }
}
