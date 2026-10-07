package com.traceability.api.application.controller;

/**
 * Seguimiento público (TR-01 a TR-03) con un {@code trackingCode} válido pero sin proyección todavía (TR-D1;
 * Q-B60-1, corregido en B6-d): 404 con el {@code ProblemDetail} fijo de B6-0, igual para las tres rutas. El mensaje no
 * lleva el {@code fundId}.
 */
public class TrackedResourceNotFoundException extends RuntimeException {
    public TrackedResourceNotFoundException() {
        super("tracked resource not found");
    }
}
