package com.traceability.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable DTO containing deterministic aggregated audit facts for a
 * campaign/convocatoria. Distinct from {@link AuditFactsDTO}, which covers
 * a single donation.
 *
 * Ref: ADR-040 (ConvocatoriaAuditFacts), resolution of C1.
 */
public record CampaignAuditFactsDTO(
    String campaignRef,
    String status,
    String organizationRef,
    BigDecimal targetAmount,
    String targetPolicy,
    BigDecimal clearedAmount,
    BigDecimal unitsDelivered,
    long distinctRecipients,
    Instant generatedAt
) {
    public CampaignAuditFactsDTO {
        if (campaignRef == null || campaignRef.isBlank()) {
            throw new IllegalArgumentException("campaignRef cannot be null or blank");
        }
        if (organizationRef == null || organizationRef.isBlank()) {
            throw new IllegalArgumentException("organizationRef cannot be null or blank");
        }
        if (distinctRecipients < 0) {
            throw new IllegalArgumentException("distinctRecipients cannot be negative");
        }
    }
}