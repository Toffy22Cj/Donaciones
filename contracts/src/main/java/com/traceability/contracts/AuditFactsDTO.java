package com.traceability.contracts;

import java.time.Instant;
import java.util.List;

/**
 * Immutable DTO containing deterministic audit facts for AI reporting.
 * 
 * Ref: ADR-015 (Aislamiento del módulo AI)
 */
public record AuditFactsDTO(
    String fundId,
    long auditFactsSequence,
    List<TransitionFactDTO> transitions,
    List<FinancialFlagDTO> financialFlags,
    Instant generatedAt
) {
}
