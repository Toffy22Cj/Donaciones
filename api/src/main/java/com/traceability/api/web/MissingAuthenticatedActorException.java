package com.traceability.api.web;

/**
 * Un parámetro {@link CurrentActor} obligatorio sin principal en la request: error de programación (el filtro JWT ya
 * habría respondido 401). Se traduce a 500.
 */
public class MissingAuthenticatedActorException extends IllegalStateException {
    public MissingAuthenticatedActorException() {
        super("No authenticated principal for a required @CurrentActor parameter");
    }
}
