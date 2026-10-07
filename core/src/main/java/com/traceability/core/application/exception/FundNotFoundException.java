package com.traceability.core.application.exception;

/**
 * El fondo no existe (plan P1.1, DD-30). Hacia fuera responde igual que "es de otra organización" (como DD-12): la
 * respuesta no revela qué ids existen.
 */
public class FundNotFoundException extends RuntimeException {
    public FundNotFoundException(String fundId) {
        super("Fund " + fundId + " does not exist");
    }
}
