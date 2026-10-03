package com.traceability.convocatoria.application.command;

/**
 * Resultado original de la edición de configuración: versión resultante (N12; implementation_plan.md §7.1).
 */
public record EditConfigurationResult(String campaignRef, long configurationVersion) {
}
