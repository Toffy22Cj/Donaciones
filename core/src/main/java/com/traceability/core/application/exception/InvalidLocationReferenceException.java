package com.traceability.core.application.exception;

/**
 * ADR-052 D1 y D4: una entrada de {@code location_reference} sin referencia o sin zona, con una sola coordenada o con
 * una fuera de rango. La lanza {@code LocationReferenceService.register} y no se guarda nada. Sin ruta HTTP: la usan
 * operación y las semillas de la demo.
 */
public class InvalidLocationReferenceException extends RuntimeException {

    public InvalidLocationReferenceException(String message) {
        super(message);
    }
}
