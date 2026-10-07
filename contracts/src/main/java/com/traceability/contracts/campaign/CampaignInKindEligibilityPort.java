package com.traceability.contracts.campaign;

/**
 * "¿Esta convocatoria acepta ahora una donación en especie de esta organización?" (ADR-029 Enmienda 1, D3; plan
 * D-CAMPAIGN). Lo implementa {@code convocatoria}, lo consume {@code core} y se conecta en {@code app}, con el mismo
 * patrón que {@code IdentityPrincipalPort}. Se consulta en el momento del registro (Q5): una convocatoria cerrada no
 * admite activos nuevos, aunque la donación se hubiera entregado antes del cierre (limitación conocida).
 */
public interface CampaignInKindEligibilityPort {

    InKindEligibility checkInKindEligibility(String campaignRef, String organizationRef);
}
