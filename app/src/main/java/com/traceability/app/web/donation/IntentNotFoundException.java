package com.traceability.app.web.donation;

/**
 * Consulta de una intención sin acceso (Enmienda 3 de ADR-037, D6): no existe, o el {@code Intent-Token} falta, no
 * corresponde o caducó. El mismo 404 para todo; el mensaje no lleva el token ni el id.
 */
public class IntentNotFoundException extends RuntimeException {
    public IntentNotFoundException() {
        super("donation intent not found");
    }
}
