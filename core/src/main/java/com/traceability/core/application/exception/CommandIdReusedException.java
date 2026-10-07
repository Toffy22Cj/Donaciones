package com.traceability.core.application.exception;

/**
 * El {@code commandId} ya lo reclamó otro comando (plan B6-c §2.1, DD-11). Un reenvío solo es un duplicado si su
 * reclamo guarda el mismo resultado; si no, devolver la respuesta del otro comando sería mentir al cliente.
 */
public class CommandIdReusedException extends RuntimeException {
    public CommandIdReusedException(String commandId) {
        super("commandId " + commandId + " was already used by another command");
    }
}
