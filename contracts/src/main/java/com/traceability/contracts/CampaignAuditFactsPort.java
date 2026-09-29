package com.traceability.contracts;

import java.util.Optional;

/**
 * Port to retrieve aggregated audit facts for a given campaign/convocatoria.
 * Distinct from {@link AuditFactsPort}, which covers a single donation.
 *
 * Ref: ADR-040 (ConvocatoriaAuditFacts), resolution of C1.
 */
public interface CampaignAuditFactsPort {
    Optional<CampaignAuditFactsDTO> getCampaignAuditFacts(String campaignRef);
}