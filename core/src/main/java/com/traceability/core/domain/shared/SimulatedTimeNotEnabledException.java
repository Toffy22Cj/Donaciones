package com.traceability.core.domain.shared;

/**
 * Se pidió fechar un hecho en el pasado ({@link EventTime#callAt}) sin que la fuente de tiempo estuviera armada. Solo
 * el perfil {@code demo-historico} la arma; en cualquier otro arranque, fechar en el pasado es un error.
 */
public class SimulatedTimeNotEnabledException extends RuntimeException {
    public SimulatedTimeNotEnabledException(String message) {
        super(message);
    }
}
