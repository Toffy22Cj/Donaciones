package com.traceability.convocatoria.application.command;

import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;

/**
 * Edición directa de configuración antes de la primera donación (Enmienda §3.2), escritura condicional sobre
 * {@code expectedConfigurationVersion}. Firma provisional, reportada en implementation_plan.md §16.
 */
public record EditConfigurationCommand(
        String commandId,
        String actorAccountId,
        String campaignRef,
        long expectedConfigurationVersion,
        ConvocatoriaConfiguration newConfiguration
) {
}
