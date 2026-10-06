package com.traceability.convocatoria.application.command;

/**
 * Cierre manual {@code OPEN → CLOSED} (Enmienda §3.4). Firma provisional, reportada en implementation_plan.md §16.
 */
public record CloseConvocatoriaCommand(String commandId, String actorAccountId, String campaignRef) {
}
