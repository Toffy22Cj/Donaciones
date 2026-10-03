package com.traceability.convocatoria.application.idempotency;

import java.util.Map;

/**
 * Registro de un comando procesado: {@code commandId}, tipo y referencia al resultado original
 * (Enmienda §3.5 N12; I1, implementation_plan.md §7.1).
 */
public record ProcessedCommand(String commandId, CommandType commandType, Map<String, String> result) {

    public ProcessedCommand {
        result = result == null ? Map.of() : Map.copyOf(result);
    }
}
