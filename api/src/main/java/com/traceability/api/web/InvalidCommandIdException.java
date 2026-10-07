package com.traceability.api.web;

/** {@code Command-Id} ausente, vacío o que no es un UUID → 400. El mensaje nunca incluye el valor recibido. */
public class InvalidCommandIdException extends RuntimeException {
    public InvalidCommandIdException() {
        super("Command-Id header must be a UUID");
    }
}
