package com.traceability.convocatoria.application.command;

import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.Visibility;

import java.time.Instant;

/**
 * Crear convocatoria (ADR-037 §2.1, §5; Enmienda §3.1, §3.5; R1). Firma provisional (Enmienda §10.2), reportada en
 * implementation_plan.md §16.
 */
public record CreateConvocatoriaCommand(
        String commandId,
        String actorAccountId,
        String organizationRef,
        String title,
        String description,
        Visibility visibility,
        Instant startDate,
        Instant endDate,
        ConvocatoriaConfiguration configuration
) {
}
