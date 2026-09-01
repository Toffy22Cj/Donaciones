package com.traceability.contracts;

import java.util.Optional;

/**
 * Port to retrieve audit facts for a given donation.
 * 
 * Ref: ADR-015 (Aislamiento del módulo AI)
 */
public interface AuditFactsPort {
    Optional<AuditFactsDTO> getAuditFacts(String donationId);
}
