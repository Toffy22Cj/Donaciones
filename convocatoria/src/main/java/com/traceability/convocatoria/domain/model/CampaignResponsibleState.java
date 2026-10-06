package com.traceability.convocatoria.domain.model;

/**
 * Contador de responsables activos por convocatoria (ADR-037 §2.5; Enmienda §4.2). Cuenta {@code EMPLOYEE}
 * y {@code ADMINISTRATOR}; el respaldo del {@code REPRESENTATIVE} no cuenta. Es un mecanismo de contención
 * de escritura (escrituras condicionales atómicas), nunca un read model.
 */
public record CampaignResponsibleState(String campaignRef, long activeResponsibleCount) {
}
