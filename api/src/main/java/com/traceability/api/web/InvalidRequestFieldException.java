package com.traceability.api.web;

/**
 * Un campo del cuerpo falta o no tiene la forma pedida → 400 (planes B6-a §2.1 y B6-c §2.2). El mensaje nombra el
 * campo para el log interno, nunca el valor recibido; el cuerpo de la respuesta es fijo.
 */
public class InvalidRequestFieldException extends RuntimeException {
    public InvalidRequestFieldException(String field) {
        super("Invalid request field: " + field);
    }
}
