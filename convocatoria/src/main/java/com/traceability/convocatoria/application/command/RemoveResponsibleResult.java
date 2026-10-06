package com.traceability.convocatoria.application.command;

/**
 * Resultado original de {@code RemoveResponsible}: asignación retirada y, si hubo reemplazo, la nueva
 * (N12; implementation_plan.md §7.1).
 */
public record RemoveResponsibleResult(String removedAssignmentId, String replacementAssignmentId) {
}
