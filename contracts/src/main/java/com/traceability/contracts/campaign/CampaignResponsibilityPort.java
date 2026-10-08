package com.traceability.contracts.campaign;

import java.util.Set;

/**
 * "¿De qué convocatorias abiertas de esta organización es responsable activo este miembro, y con qué papel?" (ADR-049
 * D9). Lo implementa {@code convocatoria}, lo consume {@code identity} antes de quitar o degradar a un miembro, y se
 * conecta en {@code app}, con el mismo patrón que {@link CampaignInKindEligibilityPort}. Solo lectura.
 */
public interface CampaignResponsibilityPort {

    /**
     * Los {@code actingRole} ({@code "ADMINISTRATOR"}, {@code "EMPLOYEE"}) de las asignaciones activas del miembro en
     * convocatorias de esa organización que no están cerradas. Vacío si no es responsable de ninguna.
     */
    Set<String> activeActingRoles(String organizationId, String accountId);
}
