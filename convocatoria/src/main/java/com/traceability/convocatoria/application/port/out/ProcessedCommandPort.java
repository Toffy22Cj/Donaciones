package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.ProcessedCommand;

import java.util.Map;
import java.util.Optional;

/**
 * Registro de comandos procesados propio del módulo (Enmienda §3.5, B2, N12; implementation_plan.md §7.1).
 * Mismo patrón que {@code core} (reclamo atómico por {@code commandId}) sin importar su código ni su colección.
 */
public interface ProcessedCommandPort {

    /**
     * Reclamo atómico dentro de la transacción en curso. Si el {@code commandId} no existía, lo reclama con su tipo
     * y devuelve vacío; si existía, devuelve el registro previo sin modificarlo. Una colisión concurrente sobre el
     * reclamo lanza {@code CommandClaimCollisionException} (reintento de la transacción completa, nunca error de dominio).
     */
    Optional<ProcessedCommand> claim(String commandId, CommandType commandType);

    /** Guarda el resultado original en el mismo documento del reclamo, antes del commit (N12). */
    void saveResult(String commandId, Map<String, String> result);

    Optional<ProcessedCommand> find(String commandId);

    /**
     * Reclamo atómico de un comando de sistema dentro de la transacción en curso, con identidad
     * {@code (commandType, commandId)} en un espacio de claves separado del de los comandos de cliente: una clave de
     * cliente nunca coincide con una de sistema. Mismo contrato que {@link #claim}: vacío si se reclamó ahora; el
     * registro previo, sin modificarlo, si ya existía; {@code CommandClaimCollisionException} ante una colisión
     * concurrente. {@code commandType} debe ser de sistema ({@link CommandType#isSystem()}).
     */
    Optional<ProcessedCommand> claimSystemCommand(CommandType commandType, String commandId);

    /** Guarda el resultado original de un comando de sistema en el mismo documento del reclamo, antes del commit. */
    void saveSystemCommandResult(CommandType commandType, String commandId, Map<String, String> result);

    Optional<ProcessedCommand> findSystemCommand(CommandType commandType, String commandId);
}
