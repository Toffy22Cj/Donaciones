package com.traceability.convocatoria.application.command;

import com.traceability.convocatoria.domain.model.ActingRole;

/**
 * {@code RemoveResponsible(campaignRef, responsibleRef, replacementRef?)} (ADR-037 §2.5; Enmienda §4.2).
 * {@code replacementActingRole} es obligatorio si hay reemplazo (decisión humana del 2026-10-01, G2): indica qué
 * operación (asignar empleado o designar administrador) rige al reemplazo. Firma provisional, reportada en §16.
 */
public record RemoveResponsibleCommand(String commandId, String actorAccountId, String campaignRef,
                                       String responsibleRef, String replacementRef,
                                       ActingRole replacementActingRole) {
}
